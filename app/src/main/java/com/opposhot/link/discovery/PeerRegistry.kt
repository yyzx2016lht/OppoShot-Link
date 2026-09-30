package com.opposhot.link.discovery

/**
 * 一个「候选对端」。
 * 注意：这里只记录「发现到的东西」，没有任何地方保存 IP 到磁盘。
 */
data class PeerCandidate(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    /** 来源："NSD" / "UDP 广播发现" / "子网兜底扫描" */
    val source: String,
    /**
     * 这个地址是否落在本机的 p2p 接口网段里。
     * true = 走 OPPO 建立的直连链路（实测 200+ Mbps），比绕办公室 Wi-Fi 快得多。
     */
    val preferred: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis(),
) {
    val key: String get() = "$host:$port"
}

/** 候选对端表。同一个 host:port 只保留最新一条。 */
object PeerRegistry {

    private const val MAX_PEERS = 20
    private val peers = linkedMapOf<String, PeerCandidate>()

    fun add(candidate: PeerCandidate): Boolean {
        synchronized(this) {
            val previous = peers[candidate.key]
            peers[candidate.key] = candidate
            val overflow = peers.size > MAX_PEERS
            while (peers.size > MAX_PEERS) {
                val oldest = peers.minByOrNull { it.value.lastSeenAt }?.key ?: break
                peers.remove(oldest)
            }
            // 同一来源、同一主机端口，认为没有新信息，避免刷屏。
            return previous == null || overflow || previous.source != candidate.source
        }
    }

    fun list(): List<PeerCandidate> = synchronized(this) {
        // 直连链路（p2p0）优先：它比绕办公室 Wi-Fi 快得多。
        peers.values.sortedWith(
            compareByDescending<PeerCandidate> { it.preferred }.thenByDescending { it.lastSeenAt }
        )
    }

    /** 有没有「直连链路」的候选（用于判断要不要切换过去）。 */
    fun hasPreferred(): Boolean = synchronized(this) {
        peers.values.any { it.preferred && System.currentTimeMillis() - it.lastSeenAt < 15_000 }
    }

    fun clear() = synchronized(this) { peers.clear() }

    /** 忘记某台设备：按 deviceId 删（没 id 就按地址删）。 */
    fun forget(deviceId: String, host: String) = synchronized(this) {
        val before = peers.size
        peers.entries.removeAll { (_, candidate) ->
            (deviceId.isNotEmpty() && candidate.deviceId == deviceId) ||
                (host.isNotEmpty() && candidate.host == host)
        }
        before - peers.size
    }
}
