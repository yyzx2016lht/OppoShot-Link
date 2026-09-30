package com.opposhot.link.discovery

import com.opposhot.link.core.AppLog
import com.opposhot.link.core.StateHub
import com.opposhot.link.net.NetInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * UDP 广播发现（Phase 1：完全对等）。
 *
 * 每台设备都做同样的事，共用一个 UDP 端口 45556：
 *   - 每 3 秒广播一次 `I_AM`（我是谁、我的 TCP 端口是多少）
 *   - 搜索期间每 3 秒广播一次 `WHO_IS`
 *   - 收到别人的 `WHO_IS` → 单播回一条 `I_AM`
 *   - 收到别人的 `I_AM` → 记为候选对端
 *
 * 「扫描开关」：用户可以主动停止查找（找满 5 分钟还没结果）。
 * 停止后**仍然保持被动**——继续听端口、仍然回别人的 `WHO_IS`，
 * 只是自己不再广播（省电，而且不会打扰别人）。
 *
 * 为什么要广播「子网广播地址」而不是只发 255.255.255.255：
 *   内核会按路由表把这个网段的广播包从对应接口发出去，
 *   所以 p2p0 上的包能被送出去，且不需要 root、不需要绑定接口。
 */
class UdpBeacon(
    private val deviceId: String,
    private val displayName: String,
    private val tcpPort: Int,
    /** host 是否在直连链路（p2p0）网段里。 */
    private val isDirectLink: (String) -> Boolean,
    private val onFound: (PeerCandidate) -> Unit,
) {

    private companion object {
        const val TAG = "UdpBeacon"
        const val PORT = 45556
        const val PREFIX = "OPPOSHOT"
        const val WHO_IS = "WHO_IS"
        const val I_AM = "I_AM"
        const val ADVERTISE_INTERVAL_MS = 3_000L
        const val ADVERTISE_LAN_MS = 8_000L
        const val SEARCH_INTERVAL_MS = 3_000L
        const val SEARCH_IDLE_MS = 15_000L
        const val BLOCK_RETRY_MS = 5_000L
        const val BLOCK_LOG_EVERY_MS = 30_000L
    }

    @Volatile private var running = false
    @Volatile private var scanning = true
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    @Volatile private var lastFoundAt = 0L
    /** 广播发送被系统拒绝（EPERM）→ 上层需要补组播锁。 */
    @Volatile private var sendBlocked = false
    @Volatile private var lastBlockedAt = 0L

    /** 广播发不出去时回调（一般是组播锁被系统收走了）。 */
    var onBlocked: (() -> Unit)? = null
    @Volatile private var lastBlockedLogAt = 0L
    @Volatile private var firstBlockedAt = 0L

    fun start() {
        if (running) return
        running = true
        scanning = true
        thread = Thread({ loop() }, "opposhot-udp").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * 是否主动广播。
     * true  = 正在主动找设备（广播 I_AM / WHO_IS）
     * false = 只被动听 + 应答别人的 WHO_IS（不广播）
     */
    fun setScanning(value: Boolean) {
        if (scanning == value) return
        scanning = value
        AppLog.info(TAG, if (value) "开始主动广播找设备" else "停止主动广播（仍保持被动监听与应答）")
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun loop() {
        val sock = createSocket() ?: run {
            AppLog.error(TAG, "UDP 端口绑定失败，广播发现不可用")
            running = false
            return
        }
        socket = sock
        AppLog.event(TAG, "UDP 广播发现已启动：监听 0.0.0.0:$PORT")

        val buffer = ByteArray(1500)
        var lastAdvertise = 0L
        var lastSearch = 0L
        var lastP2p = false

        while (running) {
            val now = System.currentTimeMillis()
            val state = StateHub.current()
            // 精简版：一对一。判断「有没有连上」用真实的已连接台数，
            // 而不是早期那个单对端时代的 tcpState 字符串。
            val connectedCount = state.devices.count { it.connected }
            val p2p = state.p2pLink

            // 没有 OPPO 直连链路 → 什么都不做，静默等待。
            // 精简版不再做局域网（wlan/eth）发现，所以这里直接就是「等用户打开跨设备互联」。
            if (!p2p) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    sock.soTimeout = 1_000
                    sock.receive(packet)
                } catch (e: java.net.SocketTimeoutException) {
                    // 继续等
                } catch (e: Exception) {
                    if (running) AppLog.error(TAG, "接收异常: ${e.message}")
                }
                continue
            }

            // OPPO 互联刚连上（p2p0 刚出现）→ 立刻广播一轮，尽快找到对方
            if (p2p && !lastP2p) {
                if (scanning) {
                    AppLog.event(TAG, "检测到 OPPO 直连链路（p2p0）刚建立，立刻广播找对方")
                }
                lastAdvertise = 0
                lastSearch = 0
            }
            lastP2p = p2p

            // 广播发不出去（EPERM）说明系统的组播锁被收走了 → 让上层补拿一把，再重试一轮
            if (sendBlocked && now - lastBlockedAt > BLOCK_RETRY_MS) {
                onBlocked?.invoke()
                lastAdvertise = 0
                lastSearch = 0
                lastBlockedAt = now
            }

            // 精简版：始终在 p2p0 上广播 I_AM（哪怕已连上，也让对方能重新发现我）。
            if (scanning) {
                val advertiseInterval =
                    if (connectedCount == 0) ADVERTISE_INTERVAL_MS else ADVERTISE_LAN_MS
                if (now - lastAdvertise >= advertiseInterval) {
                    lastAdvertise = now
                    broadcast(sock, iAmMessage())
                }

                // 只有还没连上、且最近没发现新设备时才持续问别人（WHO_IS）
                val searching = connectedCount == 0 &&
                    (lastFoundAt == 0L || now - lastFoundAt > SEARCH_IDLE_MS)
                if (searching && now - lastSearch >= SEARCH_INTERVAL_MS) {
                    lastSearch = now
                    broadcast(sock, whoIsMessage())
                }
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.soTimeout = 1_000
                sock.receive(packet)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) AppLog.error(TAG, "接收异常: ${e.message}")
                continue
            }
            handlePacket(sock, packet)
        }
        AppLog.info(TAG, "UDP 广播发现已停止")
    }

    private fun handlePacket(sock: DatagramSocket, packet: DatagramPacket) {
        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val parts = message.split("|")
        if (parts.size < 4 || parts[0] != PREFIX) return
        if (parts[2] == deviceId) return // 自己发的

        val host = packet.address?.hostAddress ?: return
        // 端口绑在 0.0.0.0 上，公共 Wi-Fi 里的包也会进来；不应答、不记录，免得暴露本机名字和 id。
        if (!isDirectLink(host)) return
        val sourcePort = packet.port

        when (parts[1]) {
            WHO_IS -> {
                AppLog.info(TAG, "收到 WHO_IS 来自 $host:$sourcePort（${parts.getOrNull(3) ?: "?"}）")
                val reply = iAmMessage().toByteArray(Charsets.UTF_8)
                try {
                    sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    AppLog.info(TAG, "已单播回复 I_AM → $host:$sourcePort")
                } catch (e: Exception) {
                    AppLog.error(TAG, "回复 I_AM 失败: ${e.message}")
                }
            }

            I_AM -> {
                val port = parts.getOrNull(4)?.toIntOrNull()
                if (port == null || port <= 0) {
                    AppLog.error(TAG, "I_AM 缺少端口: $message")
                    return
                }
                val name = parts.getOrNull(3) ?: "unknown"
                AppLog.event(TAG, "收到 I_AM 来自 $host（$name，TCP 端口 $port）")
                lastFoundAt = System.currentTimeMillis()
                onFound(
                    PeerCandidate(
                        deviceId = parts[2],
                        name = name,
                        host = host,
                        port = port,
                        source = "UDP 广播发现",
                    )
                )
            }
        }
    }

    private fun whoIsMessage(): String = "$PREFIX|$WHO_IS|$deviceId|$displayName"

    private fun iAmMessage(): String = "$PREFIX|$I_AM|$deviceId|$displayName|$tcpPort"

    private fun broadcast(sock: DatagramSocket, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val targets = NetInfo.broadcastTargets()
        val failures = mutableListOf<String>()
        for (target in targets) {
            try {
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(target), PORT))
            } catch (e: Exception) {
                failures += "$target: ${e.message}"
            }
        }
        if (failures.isEmpty()) {
            if (sendBlocked) {
                AppLog.info(TAG, "广播已恢复正常")
                sendBlocked = false
            }
            AppLog.info(TAG, "广播 $message → ${targets.joinToString(", ")}")
            return
        }

        // 广播被系统拒绝。EPERM = 没持有组播锁（Android 会直接拦下往广播地址的发送）。
        // 关键：**不要每 3 秒刷一条错误日志** —— 那会把 logcat 冲掉，
        // 真正重要的连接日志（PeerNode 握手）全被顶掉了。只在状态翻转时记一条。
        val now = System.currentTimeMillis()
        if (!sendBlocked) {
            AppLog.error(TAG, "广播被系统拒绝：${failures.joinToString("; ")}")
            onBlocked?.invoke()
        } else if (now - lastBlockedLogAt > BLOCK_LOG_EVERY_MS) {
            lastBlockedLogAt = now
            AppLog.info(TAG, "广播仍被拒绝（已重试 ${(now - firstBlockedAt) / 1000}s）")
        }
        sendBlocked = true
        lastBlockedAt = now
        if (firstBlockedAt == 0L) firstBlockedAt = now
    }

    private fun createSocket(): DatagramSocket? = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(PORT))
        }
    } catch (e: Exception) {
        AppLog.error(TAG, "UDP 端口 $PORT 绑定失败: ${e.javaClass.simpleName} ${e.message}")
        null
    }
}
