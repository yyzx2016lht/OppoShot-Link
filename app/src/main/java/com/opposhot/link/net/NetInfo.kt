package com.opposhot.link.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本机接口信息。Phase 0 只关心 IPv4。
 *
 * 注意：这里 **不硬编码任何 IP**。是哪个接口、地址段是多少，全部运行时枚举得到。
 */
data class IfaceInfo(
    val name: String,
    /** 例如 "192.168.49.1/24" */
    val cidr: String,
    /** 例如 "192.168.49.1" */
    val address: String,
    val prefixLength: Int,
    /** 例如 "192.168.49.255"，用于发子网广播；/31 及以上为 null */
    val broadcast: String?,
    val mtu: Int,
    /**
     * 名字以 "p2p" 开头的接口（Wi-Fi Direct 虚拟接口）会被标记。
     * 只按接口名判断，不按 IP 段判断。
     */
    val looksLikeP2p: Boolean,
) {
    val display: String
        get() = buildString {
            append(name)
            append("  ")
            append(cidr)
            if (looksLikeP2p) append("   [P2P 链路]")
        }

    /** 像真实局域网接口：wlan / eth / ap 开头，且是正常掩码（排除 /32 之类的点对点虚拟口）。 */
    val looksLikeLan: Boolean
        get() {
            val n = name.lowercase()
            val isLanName = n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap")
            return isLanName && prefixLength in 8..30
        }
}

object NetInfo {

    fun scan(): List<IfaceInfo> {
        val result = mutableListOf<IfaceInfo>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result

        for (nif in interfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loopback = runCatching { nif.isLoopback }.getOrDefault(true)
            if (!up || loopback) continue

            val mtu = runCatching { nif.mtu }.getOrDefault(-1)
            val looksLikeP2p = nif.name.lowercase().startsWith("p2p")

            val addresses = runCatching { nif.interfaceAddresses }.getOrNull() ?: continue
            for (ia in addresses) {
                val addr = ia.address
                if (addr !is Inet4Address) continue
                val host = addr.hostAddress ?: continue
                val prefix = ia.networkPrefixLength.toInt()
                if (prefix <= 0) continue
                result.add(
                    IfaceInfo(
                        name = nif.name,
                        cidr = "$host/$prefix",
                        address = host,
                        prefixLength = prefix,
                        broadcast = broadcastOf(addr.address, prefix),
                        mtu = mtu,
                        looksLikeP2p = looksLikeP2p,
                    )
                )
            }
        }

        // P2P 接口排在最前面，方便用户一眼看到。
        return result.sortedWith(compareByDescending<IfaceInfo> { it.looksLikeP2p }.thenBy { it.name })
    }

    /**
     * 当前最可能承载设备互连的接口：
     * 优先 p2p 接口；否则退回第一个可用 IPv4 接口（例如两端在同一 Wi-Fi 时）。
     */
    fun primary(interfaces: List<IfaceInfo>): IfaceInfo? =
        interfaces.firstOrNull { it.looksLikeP2p }
            ?: interfaces.firstOrNull { it.looksLikeLan }
            ?: interfaces.firstOrNull()

    /** 两个 IPv4 地址是否在同一网段。 */
    fun sameSubnet(a: String, b: String, prefix: Int): Boolean {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        if (pa.size != 4 || pb.size != 4 || prefix <= 0) return false
        val ia = pa.fold(0) { acc, x -> (acc shl 8) or (x and 0xFF) }
        val ib = pb.fold(0) { acc, x -> (acc shl 8) or (x and 0xFF) }
        val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
        return (ia and mask) == (ib and mask)
    }

    /**
     * 所有需要发 UDP 广播的目标地址：
     * 每个接口的子网广播地址 + 全局广播。
     * 子网广播地址会被内核按路由表送到对应接口（例如 p2p0），
     * 这样就不需要 root / SO_BINDTODEVICE。
     */
    /**
     * 广播目标地址。
     *
     * 精简版：**只在 OPPO 直连链路（p2p0）所在网段内广播**。
     * 之所以不再往 wlan/eth 广播，是因为实测在校园网 / 企业网这类多 AP 环境里，
     * 同一 SSID 的两台设备经常互相看不见（AP 隔离 / NAC 终端隔离），
     * 走局域网发现不但没用，还会让用户看到一堆连不上的"设备"。
     * 现在只认 OPPO 自己拉起来的那条直连链路：192.168.49.x 这一段。
     */
    fun broadcastTargets(interfaces: List<IfaceInfo> = scan()): List<String> {
        val p2p = interfaces.filter { it.looksLikeP2p }
        // 还没有 p2p0（OPPO 互联没开）→ 不广播，也不做任何局域网兜底
        if (p2p.isEmpty()) return emptyList()
        val targets = linkedSetOf<String>()
        p2p.mapNotNullTo(targets) { it.broadcast }
        return targets.toList()
    }

    private fun broadcastOf(address: ByteArray, prefix: Int): String? {
        if (prefix >= 31 || address.size != 4) return null
        val ip = (address[0].toInt() and 0xFF shl 24) or
            (address[1].toInt() and 0xFF shl 16) or
            (address[2].toInt() and 0xFF shl 8) or
            (address[3].toInt() and 0xFF)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val broadcast = ip or mask.inv()
        return "${broadcast ushr 24 and 0xFF}.${broadcast ushr 16 and 0xFF}." +
            "${broadcast ushr 8 and 0xFF}.${broadcast and 0xFF}"
    }
}
