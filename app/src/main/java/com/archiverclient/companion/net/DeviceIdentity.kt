package com.archiverclient.companion.net

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Who this phone says it is, and the certificate that proves it.
 *
 * Made once on first run and kept for the life of the install. Losing it means
 * every paired computer has to pair again, because the desktop pinned this exact
 * certificate as the only thing it will trust for this device.
 */
class Identity(
    val deviceId: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    /** The name our certificate is issued for, which is what the desktop dials. */
    val certificateName: String get() = Names.synthetic(deviceId)

    /** What goes in the `cert` field of `/v1/pair`. */
    fun certificatePem(): String {
        val body = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
        val wrapped = body.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$wrapped\n-----END CERTIFICATE-----\n"
    }

    /** The keystore the HTTPS server is raised on. Kept in memory only. */
    fun keyStore(password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
        }

    companion object {
        const val ALIAS = "companion"
    }
}

/**
 * [EncryptedSharedPreferences] carries a deprecation warning and no replacement
 * has landed. It is still the shortest path to a hardware-backed key wrapping
 * this phone's own private key, so it stays until there is somewhere to move to.
 */
@Suppress("DEPRECATION")
object DeviceIdentity {

    private const val PREFS = "identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PRIVATE = "private_key_pkcs8"
    private const val KEY_CERT = "certificate_der"

    @Volatile
    private var cached: Identity? = null

    fun of(context: Context): Identity =
        cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

    private fun load(context: Context): Identity {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_DEVICE_ID, null)
        val key = prefs.getString(KEY_PRIVATE, null)
        val cert = prefs.getString(KEY_CERT, null)

        if (id != null && key != null && cert != null) {
            runCatching {
                return Identity(id, decodeKey(key), decodeCert(cert))
            }
            // Unreadable is the same as absent: better to mint a fresh identity and make
            // the user pair again than to run with half an identity we cannot use.
        }
        return mint(context)
    }

    private fun mint(context: Context): Identity {
        val deviceId = newDeviceId()
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }
        val pair = generator.generateKeyPair()
        val certificate = selfSign(Names.synthetic(deviceId), pair.public, pair.private)

        prefs(context).edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_PRIVATE, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString(KEY_CERT, Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
            .apply()

        return Identity(deviceId, pair.private, certificate)
    }

    /**
     * Sixteen lowercase hex characters.
     *
     * Deliberately drawn from an alphabet the desktop's [Names.synthetic] leaves
     * untouched, so the name in our certificate is exactly the name it will ask
     * for. An id with a space or an apostrophe in it would still work, but only
     * by accident of both sides sanitising it the same way.
     */
    fun newDeviceId(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return Names.hex(bytes)
    }

    /** A display name for the pairing dialog on the desktop. */
    fun displayName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val maker = Build.MANUFACTURER?.trim().orEmpty()
        return when {
            model.isEmpty() && maker.isEmpty() -> "Android phone"
            model.startsWith(maker, ignoreCase = true) -> model
            maker.isEmpty() -> model
            else -> "$maker $model"
        }
    }

    /**
     * A self-signed certificate naming [name].
     *
     * The desktop installs this one certificate as the sole rustls trust anchor
     * and then validates the very same certificate against it
     * (`phone.rs::pinned`). That works, but only as an *end entity*: marking it
     * `CA:true` — the intuitive thing to do for something that will be used as a
     * root — makes rustls refuse the handshake outright with
     * `invalid peer certificate: Other(CaUsedAsEndEntity)`, because webpki will
     * not let a certificate be both. A trust anchor is not required to be a CA;
     * the certificate it validates is required not to be.
     *
     * So: `CA:false`, no `keyCertSign`, and the chain we present is this one
     * certificate. Verified against real rustls by `tools/verify-rustls-pinning`.
     */
    internal fun selfSign(
        name: String,
        publicKey: java.security.PublicKey,
        privateKey: PrivateKey,
    ): X509Certificate {
        val subject = X500Name("CN=$name")
        val serial = BigInteger(64, SecureRandom())
        val day = 24L * 60 * 60 * 1000
        // A day of slack behind us: phones and desktops disagree about the clock more
        // often than either of them is actually wrong.
        val from = Date(System.currentTimeMillis() - day)
        val until = Date(System.currentTimeMillis() + 3650 * day)

        val builder = JcaX509v3CertificateBuilder(subject, serial, from, until, subject, publicKey)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, name)),
        )

        // No provider is named anywhere here: Bouncy Castle only assembles the structure,
        // and the signing and the certificate object both come from the platform. That
        // keeps a Conscrypt key in a Conscrypt certificate, which is what the TLS stack
        // further down wants.
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)
        val der = builder.build(signer).encoded
        return decodeCertDer(der)
    }

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun decodeKey(base64: String): PrivateKey =
        KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(base64, Base64.NO_WRAP)))

    private fun decodeCert(base64: String): X509Certificate =
        decodeCertDer(Base64.decode(base64, Base64.NO_WRAP))

    internal fun decodeCertDer(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
}
