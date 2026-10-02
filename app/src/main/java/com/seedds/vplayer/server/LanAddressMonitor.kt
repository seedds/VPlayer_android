package com.seedds.vplayer.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes the phone's address on the local network, or null when there isn't
 * one worth showing.
 *
 * Only Wi-Fi and Ethernet count. A mobile data address is deliberately withheld
 * because a laptop cannot reach it, and printing an unreachable URL just sends
 * people hunting for a problem that is not theirs.
 */
class LanAddressMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun addresses(): Flow<String?> = callbackFlow {
        val latest = AtomicReference<String?>(null)

        fun publish() {
            val address = currentAddress()
            latest.set(address)
            trySend(address)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        publish()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }

        // Not every change raises a callback: this phone starting a hotspot
        // adds an address that no network request covers. So while there is
        // no address, look again every couple of seconds.
        launch {
            while (true) {
                delay(UNKNOWN_ADDRESS_POLL_MS)
                if (latest.get() == null) publish()
            }
        }

        awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    /** The current LAN address, or null while there is nothing usable. */
    fun currentAddress(): String? = fromActiveNetwork() ?: fromHotspotInterfaces()

    private fun fromActiveNetwork(): String? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!onLan) return null

        return connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull(::isUsableLanAddress)
            ?.hostAddress
    }

    /**
     * When the phone is the hotspot there is no Wi-Fi network to inspect, so
     * fall back to reading the interfaces directly. Without this the Upload tab
     * would claim there is no network while laptops are connected to the phone.
     */
    private fun fromHotspotInterfaces(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.filter { HOTSPOT_INTERFACE_PREFIXES.any(it.name::startsWith) }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull(::isUsableLanAddress)
            ?.hostAddress
    }.getOrNull()

    private fun isUsableLanAddress(address: java.net.InetAddress): Boolean =
        address is Inet4Address &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isAnyLocalAddress

    private companion object {
        val HOTSPOT_INTERFACE_PREFIXES = listOf("wlan", "ap", "swlan", "eth", "rndis")

        const val UNKNOWN_ADDRESS_POLL_MS = 2_000L
    }
}
