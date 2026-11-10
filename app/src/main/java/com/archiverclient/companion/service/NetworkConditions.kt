package com.archiverclient.companion.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.archiverclient.companion.net.Heartbeat
import java.net.Inet4Address

/**
 * The Android half of what the heartbeat policy needs to know.
 *
 * Registered while the service is up. When the process is dead none of this can
 * fire, which is what `NetworkWakeWorker` is for.
 */
class NetworkConditions(
    context: Context,
    private val onChanged: (String) -> Unit,
) : Heartbeat.Conditions {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var wifi = false

    /**
     * The wifi network itself, as the callback last offered it. The request
     * below asks for wifi and — by `NetworkRequest`'s defaults — not a VPN, so
     * this is the network a local address is on even while a VPN is the active
     * one.
     */
    @Volatile
    private var wifiNetwork: Network? = null

    /** The IPv4 addresses last seen on wifi, so that only a change in them wakes anybody. */
    @Volatile
    private var seenAddresses: Set<String> = emptySet()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()

        /**
         * Our address changing, as it happens: a lease that moved on the same
         * network, or — just after `onAvailable` — a reconnect's first addresses.
         *
         * IPv4 only. IPv6 privacy addresses rotate routinely, and the desktop is
         * dialled at an IPv4 address, so it only ever sees us at one of those.
         * The nudge only wakes the loop: whether anybody is told is the policy's
         * call, made against [wifiAddresses].
         */
        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
            val now = ipv4(link)
            if (now.isNotEmpty() && now != seenAddresses) {
                seenAddresses = now
                onChanged("our address changed")
            }
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) wifiNetwork = null
            refresh()
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
        refresh()
    }

    fun unregister() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    /**
     * Asked of the active network rather than of the one a callback mentioned:
     * callbacks arrive for any network matching the request, and what the policy
     * wants to know is whether wifi is what we are actually on right now.
     */
    private fun currentlyOnWifi(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun refresh() {
        val fresh = currentlyOnWifi()
        if (fresh != wifi) {
            wifi = fresh
            onChanged(if (fresh) "joined wifi" else "left wifi")
        }
    }

    override fun onWifi(): Boolean = wifi

    /** Read afresh rather than remembered, so a callback that never came cannot make it stale. */
    override fun wifiAddresses(): Set<String> {
        val network = wifiNetwork() ?: return emptySet()
        return connectivity.getLinkProperties(network)?.let(::ipv4).orEmpty()
    }

    private fun ipv4(link: LinkProperties): Set<String> =
        link.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }.toSet()

    /**
     * For sockets that must reach the local network and nowhere else: see
     * `NodeDiscovery`. Falls back to the active network while the callback has
     * yet to offer one, which it does a moment after [register] rather than
     * during it — but only when that is wifi itself and not a VPN over it.
     */
    fun wifiNetwork(): Network? = wifiNetwork ?: connectivity.activeNetwork?.takeIf { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
