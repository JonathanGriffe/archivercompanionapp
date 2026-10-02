package com.archiverclient.companion.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finding a computer whose address has moved.
 *
 * Asked only after a hello to the last known address has failed, and in two
 * steps, cheapest first:
 *
 * 1. [listen]: libp2p's own mDNS advertisement, which names the desktop by the
 *    peer id the pairing code carried. One question, three seconds. See [P2pMdns].
 * 2. [sweep]: a connect to every address in the phone's own /24 at the port the
 *    desktop keeps for phones. For when mDNS names nowhere — switched off on
 *    that computer, or unheard. See [LanSweep].
 *
 * Nothing either step says is trusted. Anyone on the wifi can answer mDNS, and
 * anything can listen on a port; both produce candidates, and the fingerprint
 * pin on the hello that follows is what decides whether one is the computer we
 * paired with.
 *
 * There is no standing subscription and no multicast lock held between
 * lookups. A lock turns off the wifi chip's multicast filtering for every
 * broadcast on the network, which costs battery around the clock to save a few
 * seconds on the rare day a computer moves.
 */
class NodeDiscovery(
    private val context: Context,
    /** The wifi network itself, so nothing here goes out over mobile data or a VPN. */
    private val wifi: () -> Network?,
) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /**
     * When each port was last swept. A computer that is simply switched off
     * fails every hello, and knocking on the whole network every fifteen minutes
     * all night for it would be noise on somebody's wifi for nothing.
     */
    private val swept = HashMap<Int, Long>()

    /** One mDNS lookup's worth: everything advertised, and the network it was heard on. */
    class Heard(private val multiaddrs: List<String>, private val subnet: Subnet?) {
        /** Where [nodeId] says it is, on this network. */
        fun addressesOf(nodeId: String): List<String> =
            P2pMdns.addressesOf(multiaddrs, nodeId, subnet)
    }

    /** Ask libp2p's mDNS who is here. Never throws; hearing nothing is an answer. */
    suspend fun listen(): Heard = withContext(Dispatchers.IO) {
        val network = wifi() ?: return@withContext Heard(emptyList(), null)
        val link = connectivity?.getLinkProperties(network)
        val via = link?.interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }

        val multiaddrs = withMulticastLock {
            try {
                P2pMdns.ask(via) { network.bindSocket(it) }
            } catch (e: IOException) {
                // Most likely 5353 held exclusively by something else on this phone,
                // which is not ours to fix. The sweep is the way round it.
                Log.w(TAG, "could not ask over mDNS", e)
                emptyList()
            }
        }
        Heard(multiaddrs, link?.let(::subnetOf))
    }

    /**
     * Every host on the phone's /24 accepting connections on [port].
     *
     * Empty when this port was swept less than [SWEEP_EVERY_MS] ago, or when
     * there is no wifi network to sweep.
     */
    suspend fun sweep(port: Int): List<String> = withContext(Dispatchers.IO) {
        val network = wifi() ?: return@withContext emptyList()
        val subnet = connectivity?.getLinkProperties(network)?.let(::subnetOf)
            ?: return@withContext emptyList()

        val now = SystemClock.elapsedRealtime()
        val last = swept[port]
        if (last != null && now - last < SWEEP_EVERY_MS) return@withContext emptyList()
        swept[port] = now

        val hosts = subnet.neighbours()
        LanSweep.accepting(hosts, port) { network.socketFactory.createSocket() }
            .also { Log.i(TAG, "swept ${hosts.size} addresses on $port: ${it.size} accepted") }
    }

    private fun subnetOf(link: LinkProperties): Subnet? =
        link.linkAddresses
            .firstOrNull { it.address is Inet4Address }
            ?.let { Subnet.of(it.address.hostAddress.orEmpty(), it.prefixLength) }

    /**
     * Wifi filters multicast out at the chip unless something holds this, so
     * without it the answers never reach us. Held for the length of one lookup.
     */
    private inline fun <T> withMulticastLock(body: () -> T): T {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = manager?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            return body()
        } finally {
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    private companion object {
        const val TAG = "NodeDiscovery"
        const val SWEEP_EVERY_MS = 60 * 60 * 1000L
    }
}
