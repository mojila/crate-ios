package com.crate.engine

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.File

/**
 * The guest's upstream DNS. libslirp forwards the guest's queries (to its
 * virtual 10.0.2.3) to the host resolver it finds in /etc/resolv.conf —
 * which doesn't exist on Android (bionic reads DNS through netd). The
 * native build patches libslirp to honor SLIRP_RESOLV_CONF instead, and
 * this writes the phone's current DNS servers there before each boot
 * (public DNS when the system offers none).
 */
object GuestDns {
    const val ENV_VAR = "SLIRP_RESOLV_CONF"
    private val FALLBACK = listOf("8.8.8.8", "1.1.1.1")

    fun ensure(context: Context) {
        val servers = linkedSetOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (net in cm.allNetworks) {
                val props = try {
                    cm.getLinkProperties(net)
                } catch (_: Exception) {
                    null
                }
                props?.dnsServers?.forEach { addr ->
                    val ip = (addr.hostAddress ?: "").substringBefore("%")
                    if (ip.matches(Regex("[0-9a-fA-F.:]+"))) servers.add(ip)
                }
            }
        } catch (_: Exception) {
        }
        if (servers.isEmpty()) servers.addAll(FALLBACK)
        try {
            val dir = File(context.filesDir, "slirp")
            dir.mkdirs()
            val conf = File(dir, "resolv.conf")
            conf.writeText(buildString {
                appendLine("# Crate: phone DNS for the guest, see SLIRP_RESOLV_CONF.")
                servers.take(3).forEach { appendLine("nameserver $it") }
            })
            Os.setenv(ENV_VAR, conf.absolutePath, true)
        } catch (_: Exception) {
        }
    }
}
