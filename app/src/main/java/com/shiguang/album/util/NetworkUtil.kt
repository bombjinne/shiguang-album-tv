package com.shiguang.album.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 局域网信息 & 时间格式等小工具 */
object NetworkUtil {

    /**
     * 取当前局域网 IPv4 地址（优先 Wi-Fi，其次有线，最后其它）。
     * 电视通常走 Wi-Fi，部分盒子走网线，所以两个都覆盖。
     */
    fun lanIpAddress(): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            interfaces.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                val name = nif.name.orEmpty().lowercase(Locale.ROOT)
                val score = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.startsWith("swlan") -> 3
                    else -> 2
                }
                nif.inetAddresses?.toList().orEmpty().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        candidates.add(score to addr.hostAddress.orEmpty())
                    }
                }
            }
        }
        return candidates
            .filter { it.second.isNotBlank() && !it.second.startsWith("169.254.") }
            .sortedBy { it.first }
            .firstOrNull()?.second
            ?: "127.0.0.1"
    }

    /**
     * 当前所在网段前缀，例如 "192.168.31."。
     *
     * 用途：添加 WebDAV / SMB 时把主机地址默认填成同网段，
     * 用户只需补最后一段（NAS 几乎总在同一网段，省掉敲前三段）。
     */
    fun lanSubnetPrefix(): String {
        val ip = lanIpAddress()
        val parts = ip.split(".")
        return if (parts.size == 4 && parts.all { it.toIntOrNull() != null } && !ip.startsWith("127.")) {
            parts.take(3).joinToString(".") + "."
        } else {
            "192.168.1."
        }
    }

    fun clockText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    fun dateText(): String = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
}
