package com.wififtp.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    // Mobile-data / VPN / virtual interfaces we never want to show.
    private val IGNORED_PREFIXES =
        listOf("rmnet", "ccmni", "pdp", "ppp", "tun", "dummy", "lo", "v4-", "clat", "ip6", "sit", "ifb")

    /**
     * Returns the phone's local IPv4 addresses on Wi-Fi, Wi-Fi hotspot, Ethernet or USB tethering,
     * most likely first. Works when the phone is a Wi-Fi client or is sharing its own hotspot.
     */
    @Suppress("DEPRECATION")
    fun localIpv4Addresses(context: Context): List<String> {
        val found = LinkedHashMap<String, Int>() // ip -> priority (lower = better)

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (IGNORED_PREFIXES.any { name.startsWith(it) }) continue
                val priority =
                    if (name.startsWith("wlan") || name.startsWith("swlan") ||
                        name.startsWith("ap") || name.startsWith("eth")
                    ) 0 else 1
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        found.putIfAbsent(ip, priority)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Fallback / complement: ask ConnectivityManager about Wi-Fi and Ethernet networks.
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val ok = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!ok) continue
                val props = cm.getLinkProperties(network) ?: continue
                for (la in props.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        a.hostAddress?.let { found.putIfAbsent(it, 0) }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return found.entries.sortedBy { it.value }.map { it.key }
    }
}
