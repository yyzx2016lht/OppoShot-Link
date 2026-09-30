package com.opposhot.link.net

import android.content.Context
import android.os.SystemClock
import com.opposhot.link.core.AppLog
import com.opposhot.link.core.FileReceiver
import com.opposhot.link.core.MediaLibrary
import com.opposhot.link.core.StateHub
import com.opposhot.link.discovery.PeerCandidate
import com.opposhot.link.discovery.PeerRegistry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 对等的 TCP 节点（一对一）。
 *
 * 每台设备都做同样两件事：
 *   1. 监听自己的 45555 端口（只接受来自 p2p0 网段的连接）；
 *   2. 主动去连「发现到的对端」。
 *
 * 两台设备可能同时互相拨号，这时用「deviceId 小的那方发起的是规范连接」只保留一条。
 * 已经连着一台时，别的设备连进来会被直接关掉。
 *
 * 被反复拒绝的地址会退避（1s→3s→8s→20s→30s），避免出现重连风暴。
 *
 * 所有计时都用 [SystemClock.elapsedRealtime]：系统时间被调整时不会误判超时。
 */
class PeerNode(
    private val port: Int,
    private val deviceId: String,
    private val displayName: String,
    private val context: Context,
    /** host 是否在直连链路（p2p0）网段里。 */
    private val isDirectLink: (String) -> Boolean,
) {

    /** 上层关心的事件。 */
    interface Listener {
        fun onMediaFromPeer(item: MediaItem, thumb: ByteArray?, fromId: String, fromName: String)
        fun onTransferProgress(id: String, percent: Int)
        fun onTransferFailed(id: String, reason: String)
        fun onRemoteFileDone(id: String, ok: Boolean, note: String, reason: String)
    }

    var listener: Listener? = null

    /** 连接的增减发生变化（界面要刷新设备列表）。 */
    var onPeersChanged: (() -> Unit)? = null

    /**
     * 「和这台设备通不通这类内容」的判断。kind: image / video / screenshot
     * 由 LinkController 接上 PeerSettings；返回 false 就不发、也不收。
     */
    var policyGate: ((deviceId: String, kind: String) -> Boolean)? = null

    /**
     * 「允不允许把内容发过去」的判断（比 policyGate 多考虑了「仅接收」）。
     * 接收方向不受它影响 —— 「仅接收」时照样收，只是不往外发。
     */
    var sendGate: ((deviceId: String, kind: String) -> Boolean)? = null

    /**
     * 「允许连这台设备吗」。连接必须经过用户同意，或者该设备开了「自动连接」。
     */
    var mayDial: ((deviceId: String, host: String) -> Boolean)? = null

    private fun allowed(session: Session, kind: String): Boolean {
        val did = session.peerDeviceId
        if (did.isEmpty()) return false
        return policyGate?.invoke(did, kind) ?: true
    }

    /** 发送方向：多问一层 sendGate（「仅接收」在这里被挡住）。 */
    private fun canSend(session: Session, kind: String): Boolean {
        val did = session.peerDeviceId
        if (did.isEmpty()) return false
        if (sendGate?.invoke(did, kind) == false) return false
        return policyGate?.invoke(did, kind) ?: true
    }

    /** 接收文件用；由 LinkController 注入。 */
    var fileReceiver: FileReceiver? = null

    private companion object {
        const val TAG = "PeerNode"
        const val PING_INTERVAL_MS = 10_000L
        const val RX_TIMEOUT_MS = 40_000L
        const val HANDSHAKE_TIMEOUT_MS = 6_000L
        const val DIAL_RETRY_MS = 1_500L
        const val MEDIA_PAIR_TIMEOUT_MS = 5_000L
        const val CHUNK_BYTES = 256 * 1024
        const val SEND_TIMEOUT_MS = 30_000L
        /** 连接活了不到这么久就断了，视为「被拒绝」，要退避。 */
        const val QUICK_DEATH_MS = 2_000L
    }

    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private var dialThread: Thread? = null
    private var keepAliveThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    private val lock = Any()
    private val sessions = CopyOnWriteArrayList<Session>()

    /** 每条对端一条会话：key = 对端 deviceId。 */
    private val activeByPeer = ConcurrentHashMap<String, Session>()

    /** 失败退避用。 */
    private val nextTryAt = ConcurrentHashMap<String, Long>()
    private val failCount = ConcurrentHashMap<String, Int>()

    /** 正在发起中的连接（key = host:port），避免同一个地址同时连好几次。 */
    private val dialing = ConcurrentHashMap.newKeySet<String>()

    private var pingCounter = 0

    /** 被取消的媒体 id。每次收到新的 ACCEPT 会先清掉旧记录，否则取消过的文件再也要不到。 */
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    val isConnected: Boolean get() = activeByPeer.values.any { it.resolved && !it.closed }

    /** 当前连着的对端名字（给界面用）。 */
    fun connectedPeerNames(): List<String> =
        activeByPeer.values.filter { it.resolved && !it.closed }
            .map { it.peerName.ifEmpty { it.remoteHost } }

    /** 当前连着的对端（id / 名字 / 地址）。 */
    fun connectedPeers(): List<Triple<String, String, String>> =
        activeByPeer.values.filter { it.resolved && !it.closed }
            .map { Triple(it.peerDeviceId, it.peerName.ifEmpty { it.remoteHost }, it.remoteHost) }

    /** 当前连着的对端 deviceId 集合。 */
    fun connectedPeerIds(): Set<String> = activeByPeer.values
        .filter { it.resolved && !it.closed }
        .map { it.peerDeviceId }
        .toSet()

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        if (running) return
        running = true

        acceptThread = Thread({ acceptLoop() }, "opposhot-accept").also {
            it.isDaemon = true
            it.start()
        }
        dialThread = Thread({ dialLoop() }, "opposhot-dial").also {
            it.isDaemon = true
            it.start()
        }
        keepAliveThread = Thread({ keepAliveLoop() }, "opposhot-keepalive").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        sessions.forEach { it.close() }
        sessions.clear()
        activeByPeer.clear()
        nextTryAt.clear()
        failCount.clear()
        acceptThread?.interrupt()
        dialThread?.interrupt()
        keepAliveThread?.interrupt()
        dialing.clear()
        StateHub.update { it.copy(tcpState = "已停止", listenState = "已停止", connectedPeers = "-") }
        AppLog.info(TAG, "对等节点已停止")
    }

    // ---------------------------------------------------------------- 对外发送

    /**
     * 发送一条「新媒体」：先发元数据（文本帧），再发缩略图（二进制帧）。
     * 两帧在同一条连接上有序到达，对端按 id 配对。
     */
    fun sendMediaEvent(item: MediaItem, thumb: ByteArray?) {
        val targets = connectedSessions().filter { canSend(it, item.contentKind) }
        if (targets.isEmpty()) {
            AppLog.info(TAG, "没有允许接收这类内容的对端，跳过（${item.displayName}）")
            return
        }
        targets.forEach { session ->
            session.send(Proto.TYPE_MEDIA_EVENT, item.payload())
            if (thumb != null) {
                session.sendBytes(Proto.TYPE_MEDIA_THUMB, ThumbFrame.encode(item.id, thumb))
            }
        }
    }

    /** 告诉所有对端「我要这个文件的原图」。 */
    fun accept(id: String): Boolean {
        val targets = connectedSessions()
        if (targets.isEmpty()) {
            AppLog.error(TAG, "还没连上对端，无法请求原图（$id）")
            StateHub.update { it.copy(lastError = "还没连上对端，原图请求发不出去") }
            return false
        }
        targets.forEach { it.send(Proto.TYPE_ACCEPT, id) }
        return true
    }

    /**
     * 只向「当初发来这条媒体的那台设备」请求原图。
     *
     * 多对多时必须这样做：否则点一条通知会把 ACCEPT 广播给所有对端，
     * 对面的机器会开始传一份它手上根本没有的文件。
     */
    fun acceptTo(peerDeviceId: String, id: String): Boolean {
        val session = activeByPeer[peerDeviceId]?.takeIf { it.resolved && !it.closed }
        if (session == null) {
            AppLog.error(TAG, "没能向 $peerDeviceId 请求原图（$id）：不在线")
            StateHub.update { it.copy(lastError = "对方已离线，暂时取不到原图") }
            return false
        }
        return session.send(Proto.TYPE_ACCEPT, id)
    }

    /** 只把「收完了 / 收失败」告诉那台发文件的设备。 */
    fun sendFileDoneTo(peerDeviceId: String, id: String, ok: Boolean, note: String) {
        val payload = FileDone(id, ok, note).payload()
        val session = activeByPeer[peerDeviceId]?.takeIf { it.resolved && !it.closed }
        if (session != null) {
            session.send(Proto.TYPE_FILE_DONE, payload)
        } else {
            connectedSessions().forEach { it.send(Proto.TYPE_FILE_DONE, payload) }
        }
    }

    /** 用户取消：本地停止 + 通知对端停止。 */
    fun cancelTransfer(id: String) {
        cancelledIds.add(id)
        if (fileReceiver?.activeId() == id) {
            fileReceiver?.cancel(id)
            AppLog.event(TAG, "用户取消，已停止接收 $id")
        }
        connectedSessions().forEach { it.send(Proto.TYPE_CANCEL, id) }
    }

    private fun connectedSessions(): List<Session> =
        activeByPeer.values.filter { it.resolved && !it.closed }

    // ---------------------------------------------------------------- 发送文件

    /** 只在会话自己的串行发送线程上跑（见 [Session.queueFile]）。 */
    private fun doSendFile(id: String, session: Session) {
        if (!session.resolved || session.closed) {
            AppLog.error(TAG, "发送 $id 失败：连接已断开")
            return
        }

        val info = MediaLibrary.info(context, id)
        if (info == null) {
            AppLog.error(TAG, "发送 $id 失败：相册里已经找不到这张了")
            session.send(
                Proto.TYPE_FILE_DONE,
                FileDone(id, false, "相册里已找不到这张照片", FileDone.REASON_GONE).payload(),
            )
            return
        }

        val sha = MediaLibrary.sha256(context, id)
        if (sha == null) {
            // 记录还在但读不出内容 —— 安卓「部分照片授权」时就是这个情况：
            // App 只能看到用户勾选的那几张，其余的查得到、却打不开。
            AppLog.error(TAG, "发送 $id 失败：读不出内容（可能未授权访问这张）")
            session.send(
                Proto.TYPE_FILE_DONE,
                FileDone(id, false, "读不出内容，可能未授权访问这张照片", FileDone.REASON_UNREADABLE)
                    .payload(),
            )
            return
        }

        AppLog.event(TAG, "开始发送原图：${info.name}（${info.size} 字节，sha256=${sha.take(12)}…）")
        session.send(
            Proto.TYPE_FILE_BEGIN,
            FileBegin(id, info.name, info.mime, info.size, CHUNK_BYTES, sha).payload(),
        )

        var index = 0
        var sent = 0L
        var lastPercent = -1
        var aborted = false
        val buffer = ByteArray(CHUNK_BYTES)
        val input = MediaLibrary.openStream(context, id)
        if (input == null) {
            session.send(Proto.TYPE_FILE_DONE, FileDone(id, false, "打不开文件").payload())
            return
        }

        input.use { stream ->
            while (true) {
                if (cancelledIds.remove(id)) {
                    AppLog.event(TAG, "发送已被取消：${info.name}（已发 $sent 字节）")
                    aborted = true
                    break
                }
                val read = try {
                    stream.read(buffer)
                } catch (e: Exception) {
                    AppLog.error(TAG, "读取失败: ${e.message}")
                    -1
                }
                if (read <= 0) break

                val data = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                val ok = session.sendBytesBlocking(
                    Proto.TYPE_CHUNK,
                    ChunkFrame.encode(id, index, data),
                    SEND_TIMEOUT_MS,
                )
                if (!ok) {
                    AppLog.error(TAG, "分片发送失败（index=$index），中止本次传输")
                    listener?.onTransferFailed(id, "分片发送失败")
                    return
                }
                sent += read
                index++
                if (info.size > 0) {
                    val percent = ((sent * 100) / info.size).toInt()
                    if (percent / 10 != lastPercent / 10) {
                        lastPercent = percent
                        listener?.onTransferProgress(id, percent)
                    }
                }
            }
        }

        if (aborted) return
        session.send(Proto.TYPE_FILE_END, id)
        AppLog.event(TAG, "原图已发完：${info.name}（$sent 字节，$index 个分片）")
    }

    // ---------------------------------------------------------------- 监听

    private fun acceptLoop() {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "监听 $port 端口失败: ${e.javaClass.simpleName} ${e.message}")
            StateHub.update {
                it.copy(listenState = "监听失败", lastError = "监听 $port 失败: ${e.message}")
            }
            return
        }
        serverSocket = socket
        AppLog.event(TAG, "本机已监听 TCP 0.0.0.0:$port（等待对端连入）")
        StateHub.update { it.copy(listenState = "监听中 :$port") }

        while (running) {
            val accepted = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) AppLog.error(TAG, "accept 异常: ${e.message}")
                break
            }
            val remote = accepted.inetAddress?.hostAddress.orEmpty()
            // 端口监听在 0.0.0.0 上，同一个 Wi-Fi 里的任何人都能连进来；只认 p2p0 直连网段。
            if (!isDirectLink(remote)) {
                AppLog.info(TAG, "拒绝非直连网段的连接：$remote:${accepted.port}")
                runCatching { accepted.close() }
                continue
            }
            AppLog.event(TAG, "收到来自 $remote:${accepted.port} 的连接")
            launch(Session(accepted, outgoing = false))
        }
    }

    private fun launch(session: Session) {
        sessions.add(session)
        session.start()
    }

    // ---------------------------------------------------------------- 主动连接

    /**
     * 拨号循环。
     *
     * 每一轮把所有「用户允许连、且当前没连、也没在退避期内」的候选各起一个后台线程去连，
     * **自己不阻塞**。以前这里是「连上就 join 到连接结束」，结果一台设备一辈子只能主动连出一台；
     * 改成并行拨号后，3 台设备才能真正同时连成多对多。
     */
    private fun dialLoop() {
        var lastSwitchAt = 0L
        while (running) {
            val now = SystemClock.elapsedRealtime()
            val candidates = PeerRegistry.list()
            if (candidates.isEmpty()) {
                sleep(DIAL_RETRY_MS)
                continue
            }

            // 已经有连接（含刚连上还没握完手的）的地址就不再重复拨
            val busyHosts = (activeByPeer.values.toList() + sessions.toList())
                .filter { !it.closed }
                .map { it.remoteHost }
                .toSet()

            // 现在走的是慢链路，而直连链路已经可用 → 切过去（每 30 秒最多切一次）
            if (PeerRegistry.hasPreferred() && now - lastSwitchAt > 30_000) {
                lastSwitchAt = now
                val slow = activeByPeer.values.firstOrNull { it.resolved && !it.closed && !it.isDirectLink }
                val better = candidates.firstOrNull {
                    it.preferred && it.host !in busyHosts
                }
                if (slow != null && better != null) {
                    AppLog.event(
                        TAG,
                        "发现更快的直连链路，切换过去（当前走 ${slow.remoteHost}，改走 ${better.host}）",
                    )
                    slow.close()
                }
            }

            // 同一台设备通常会被广播到多个地址（p2p0 直连 + 同一个 Wi-Fi）。
            // 以前是「每个地址都并行去连」，结果谁先到就连谁 —— 常常连到绕路由器的慢链路，
            // 而 p2p0 直连明明已经出现。现在一台设备只拨「最好的那个」地址：
            //   直连优先；最好的那个正在失败退避时，才轮到次优的地址。
            val groups = LinkedHashMap<String, MutableList<PeerCandidate>>()
            for (candidate in candidates) {
                val key = candidate.deviceId
                    .takeIf { it.isNotEmpty() && it != "unknown" } ?: "host:${candidate.host}"
                groups.getOrPut(key) { mutableListOf() }.add(candidate)
            }

            for (list in groups.values) {
                if (!running) return
                // 直连（p2p0）排前面
                val pick = list.sortedByDescending { it.preferred }
                    .firstOrNull { now >= (nextTryAt[it.key] ?: 0L) }
                    ?: continue
                if (pick.host in busyHosts) continue
                // 连接必须经过用户同意（或该设备开了「自动连接」）
                if (mayDial?.invoke(pick.deviceId, pick.host) == false) continue
                if (!dialing.add(pick.key)) continue
                if (list.size > 1) {
                    AppLog.info(
                        TAG,
                        "对端 ${pick.name} 有 ${list.size} 个可用地址，本轮选 ${pick.host}" +
                            if (pick.preferred) "（直连）" else "（局域网）",
                    )
                }
                Thread({
                    try {
                        dial(pick)
                    } finally {
                        dialing.remove(pick.key)
                    }
                }, "opposhot-dial-${pick.host}").also {
                    it.isDaemon = true
                    it.start()
                }
            }
            sleep(DIAL_RETRY_MS)
        }
    }

    /** 断开与某台对端的连接。 */
    fun disconnect(peerDeviceId: String) {
        activeByPeer[peerDeviceId]?.close()
    }

    /**
     * 除了这台设备之外，是否还有别的设备正在连接/已连接。
     * 一对一模式下用：已经连着一台时，就不要自动再去连第二台。
     */
    fun hasOtherActivePeer(exceptPeerId: String): Boolean = synchronized(lock) {
        activeByPeer.any { (id, session) -> id != exceptPeerId && !session.closed }
    }

    /** 失败退避：1s → 3s → 8s → 20s → 30s（上限）。 */
    private fun backoffMs(failures: Int): Long = when {
        failures <= 1 -> 1_000L
        failures == 2 -> 3_000L
        failures == 3 -> 8_000L
        failures == 4 -> 20_000L
        else -> 30_000L
    }

    private fun noteDialFailure(key: String, host: String) {
        val fails = (failCount[key] ?: 0) + 1
        failCount[key] = fails
        nextTryAt[key] = SystemClock.elapsedRealtime() + backoffMs(fails)
        AppLog.info(TAG, "与 $host 的连接很快断开（第 $fails 次），${backoffMs(fails) / 1000}s 后再试")
    }

    private fun noteDialSuccess(key: String) {
        failCount.remove(key)
        nextTryAt.remove(key)
    }

    /** 连上就返回（不再阻塞到连接结束）。 */
    private fun dial(candidate: PeerCandidate): Boolean {
        StateHub.update {
            it.copy(tcpState = "正在连接 ${candidate.host}:${candidate.port}（${candidate.source}）")
        }
        AppLog.info(TAG, "尝试连接 ${candidate.host}:${candidate.port}（来源 ${candidate.source}）")

        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            // 大一点的缓冲更抗延迟（局域网 RTT 可能有上百毫秒）
            runCatching { socket.sendBufferSize = 1 shl 20 }
            runCatching { socket.receiveBufferSize = 1 shl 20 }
            socket.connect(InetSocketAddress(candidate.host, candidate.port), 5_000)

            val session = Session(socket, outgoing = true, dialKey = candidate.key)
            launch(session)
            true
        } catch (e: Exception) {
            AppLog.error(
                TAG,
                "连接 ${candidate.host}:${candidate.port} 失败: ${e.javaClass.simpleName} ${e.message}",
            )
            StateHub.update {
                it.copy(
                    tcpState = "连接失败，正在重试…",
                    lastError = "${candidate.host}:${candidate.port} ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            noteDialFailure(candidate.key, candidate.host)
            runCatching { socket.close() }
            false
        }
    }

    // ---------------------------------------------------------------- 保活

    private fun keepAliveLoop() {
        while (running) {
            sleep(2_000)
            val now = SystemClock.elapsedRealtime()

            sessions.forEach { session ->
                if (!session.closed && !session.resolved &&
                    now - session.startedAt > HANDSHAKE_TIMEOUT_MS
                ) {
                    AppLog.error(TAG, "握手超时，关闭会话（${session.label}）")
                    session.close()
                }
            }
            sessions.forEach { it.flushPendingMediaIfStale() }

            activeByPeer.values.toList().forEach { session ->
                if (session.closed) return@forEach
                if (now - session.lastRxAt > RX_TIMEOUT_MS) {
                    AppLog.error(TAG, "对端 ${RX_TIMEOUT_MS / 1000}s 无响应，关闭连接准备重连")
                    session.close()
                    return@forEach
                }
                if (now - session.lastPingAt >= PING_INTERVAL_MS) {
                    session.lastPingAt = now
                    pingCounter++
                    session.send(Proto.TYPE_PING, "ping#$pingCounter")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 会话

    private inner class Session(
        private val socket: Socket,
        val outgoing: Boolean,
        /** 本机主动拨出去时的候选地址 key；被动接受时为 null。 */
        private val dialKey: String? = null,
    ) {

        private var input: BufferedInputStream? = null
        private var output: BufferedOutputStream? = null
        private var thread: Thread? = null

        /**
         * 所有待发送的帧都先进这个队列，由本会话专属的写线程真正写 socket。
         *
         * 为什么必须这样：如果直接在 UI 线程写 socket，Android 会抛
         * NetworkOnMainThreadException（message 为 null），而且会把连接写坏。
         */
        private val outQueue = LinkedBlockingQueue<ByteArray>(64)
        private var writerThread: Thread? = null

        /**
         * 原图一个一个发：接收端同一时刻只收一个文件，
         * 两个文件的分片交错写到同一条连接上，两个都会坏。
         */
        private val fileQueue: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "opposhot-sendfile").apply { isDaemon = true }
        }
        private val queuedIds = ConcurrentHashMap.newKeySet<String>()

        val startedAt = SystemClock.elapsedRealtime()
        @Volatile var lastRxAt = SystemClock.elapsedRealtime()
        @Volatile var lastPingAt = SystemClock.elapsedRealtime()
        @Volatile var resolved = false
        @Volatile var closed = false
        @Volatile var peerDeviceId = ""
        @Volatile var peerName = ""

        // MEDIA_EVENT 与 MEDIA_THUMB 按到达顺序配对
        private var pendingItem: MediaItem? = null
        private var pendingThumb: ByteArray? = null
        private var pendingAt = 0L

        val remoteHost: String get() = socket.inetAddress?.hostAddress ?: "?"
        val remotePort: Int get() = socket.port

        /** 这条连接是否走直连链路（p2p0）。 */
        val isDirectLink: Boolean get() = this@PeerNode.isDirectLink(remoteHost)

        fun livedMs(): Long = SystemClock.elapsedRealtime() - startedAt

        /** 这条连接是谁发起的（本机发起就是我，对端发起就是对面）。 */
        private val initiatorDid: String get() = if (outgoing) deviceId else peerDeviceId

        private val isCanonical: Boolean
            get() {
                if (peerDeviceId.isEmpty()) return false
                return initiatorDid == minOf(deviceId, peerDeviceId)
            }

        val label: String
            get() = buildString {
                append(if (outgoing) "本机发起" else "对端发起")
                append(" ")
                append("$remoteHost:$remotePort")
                if (peerName.isNotEmpty()) append("（$peerName）")
            }

        fun start() {
            thread = Thread({ run() }, "opposhot-session").also {
                it.isDaemon = true
                it.start()
            }
        }

        private fun run() {
            try {
                socket.tcpNoDelay = true
                runCatching { socket.sendBufferSize = 1 shl 20 }
                runCatching { socket.receiveBufferSize = 1 shl 20 }
                output = BufferedOutputStream(socket.getOutputStream())
                input = BufferedInputStream(socket.getInputStream())
                startWriter()

                AppLog.event(TAG, "TCP 已建立（$label）")
                send(Proto.TYPE_HELLO, helloPayload())

                while (running && !closed) {
                    val frame = Proto.read(input ?: break) ?: break
                    lastRxAt = SystemClock.elapsedRealtime()
                    handle(frame)
                }
            } catch (e: IOException) {
                if (!closed) {
                    AppLog.error(TAG, "会话 I/O 异常（$label）: ${e.javaClass.simpleName} ${e.message}")
                }
            } catch (e: Exception) {
                if (!closed) {
                    AppLog.error(TAG, "会话异常（$label）: ${e.javaClass.simpleName} ${e.message}")
                }
            } finally {
                close()
                onClosed()
            }
        }

        private fun startWriter() {
            writerThread = Thread({
                while (!closed) {
                    val bytes = try {
                        outQueue.take()
                    } catch (e: InterruptedException) {
                        break
                    }
                    val out = output ?: break
                    try {
                        out.write(bytes)
                        out.flush()
                    } catch (e: IOException) {
                        AppLog.error(TAG, "写入失败（$label）: ${e.javaClass.simpleName} ${e.message}")
                        close()
                        break
                    }
                }
            }, "opposhot-writer").also {
                it.isDaemon = true
                it.start()
            }
        }

        private fun handle(frame: Proto.Frame) {
            // CHUNK / PING / PONG 太频繁（1 GB 文件就是 4000 个分片），逐帧记日志会拖垮主线程。
            // MEDIA_THUMB 是二进制，绝不能转成字符串再进日志（会把内存撑爆）。
            if (!Proto.isHighFrequency(frame.type)) {
                val shown = if (frame.type == Proto.TYPE_MEDIA_THUMB) "${frame.payload.size} 字节" else frame.text
                AppLog.rx(TAG, "${Proto.typeName(frame.type)} <- $label: $shown")
            }

            when (frame.type) {
                Proto.TYPE_HELLO -> {
                    applyPeerInfo(frame.text)
                    send(Proto.TYPE_HELLO_ACK, helloPayload())
                    resolve()
                }
                Proto.TYPE_HELLO_ACK -> {
                    applyPeerInfo(frame.text)
                    resolve()
                }
                Proto.TYPE_PING -> send(Proto.TYPE_PONG, frame.text)
                Proto.TYPE_MEDIA_EVENT -> onMediaEvent(frame.text)
                Proto.TYPE_MEDIA_THUMB -> onMediaThumb(frame.payload)
                // 剪贴板功能已移除：对端如果还在发 CLIP，直接忽略（向前兼容，不报错）
                Proto.TYPE_CLIP -> AppLog.info(TAG, "收到剪贴板消息，已忽略（本版本不支持剪贴板）")
                Proto.TYPE_ACCEPT -> {
                    // 安全性由 acceptLoop 里的 p2p0 网段校验保证：
                    // 能走到这里的对端一定在 OPPO 直连网段内，而那个网段只有互联的两台设备。
                    // 所以这里不再校验「对方有没有收到过这个文件的推送」——
                    // 那种校验会打断「最近收到」里的历史图片（那些 id 是上一次会话推送的）。
                    val id = frame.text.trim()
                    AppLog.event(TAG, "对端请求原图：$id")
                    queueFile(id)
                }
                Proto.TYPE_DECLINE -> AppLog.info(TAG, "对端忽略了这个文件：${frame.text}")
                Proto.TYPE_FILE_BEGIN -> {
                    val begin = FileBegin.from(frame.text)
                    if (begin == null) {
                        AppLog.error(TAG, "FILE_BEGIN 解析失败: ${frame.text}")
                    } else {
                        fileReceiver?.onBegin(begin, peerDeviceId)
                    }
                }
                Proto.TYPE_CHUNK -> {
                    val decoded = ChunkFrame.decode(frame.payload)
                    if (decoded == null) {
                        AppLog.error(TAG, "CHUNK 解析失败（${frame.payload.size} 字节）")
                    } else {
                        fileReceiver?.onChunk(peerDeviceId, decoded.first, decoded.second, decoded.third)
                    }
                }
                Proto.TYPE_FILE_END -> fileReceiver?.onEnd(peerDeviceId, frame.text.trim())
                Proto.TYPE_FILE_DONE -> {
                    val done = FileDone.from(frame.text)
                    AppLog.event(
                        TAG,
                        if (done.ok) "对端已保存：${done.note}" else "对端保存失败：${done.note}",
                    )
                    listener?.onRemoteFileDone(done.id, done.ok, done.note, done.reason)
                }
                Proto.TYPE_CANCEL -> {
                    val id = frame.text.trim()
                    AppLog.event(TAG, "对端取消了传输：$id")
                    cancelledIds.add(id)
                    if (fileReceiver?.activeId() == id) fileReceiver?.cancel(id)
                    listener?.onTransferFailed(id, "对方已取消")
                }
                else -> Unit
            }
        }

        fun queueFile(id: String) {
            if (!queuedIds.add(id)) {
                AppLog.info(TAG, "这个文件已经在发送队列里了：$id")
                return
            }
            cancelledIds.remove(id)
            try {
                fileQueue.execute {
                    try {
                        doSendFile(id, this)
                    } finally {
                        queuedIds.remove(id)
                    }
                }
            } catch (e: Exception) {
                // 会话已关闭，队列不再接收任务
                queuedIds.remove(id)
            }
        }

        // pendingItem / pendingThumb 会被会话线程和保活线程同时碰，下面三个入口都要加锁。
        @Synchronized
        private fun onMediaEvent(raw: String) {
            val item = MediaItem.fromPayload(raw)
            if (item == null) {
                AppLog.error(TAG, "MEDIA_EVENT 解析失败: $raw")
                return
            }
            if (!allowed(this, item.contentKind)) {
                AppLog.info(
                    TAG,
                    "对端发来${item.contentKind}，但与该设备的开关是关的，已忽略（${item.displayName}）",
                )
                return
            }
            pendingItem = item
            pendingAt = SystemClock.elapsedRealtime()
            pendingThumb = null
            maybeDeliverMedia()
        }

        @Synchronized
        private fun onMediaThumb(payload: ByteArray) {
            val decoded = ThumbFrame.decode(payload)
            if (decoded == null) {
                AppLog.error(TAG, "MEDIA_THUMB 解析失败（${payload.size} 字节）")
                return
            }
            val (id, image) = decoded
            val item = pendingItem
            if (item == null) {
                AppLog.error(TAG, "先收到缩略图后收到事件，丢弃（id=$id）")
                return
            }
            if (item.id != id) {
                AppLog.error(TAG, "缩略图 id 不匹配：收到 $id，期望 ${item.id}")
            }
            pendingThumb = image
            maybeDeliverMedia()
        }

        private fun maybeDeliverMedia() {
            val item = pendingItem ?: return
            val thumb = pendingThumb ?: return
            deliverMedia(item, thumb)
        }

        @Synchronized
        fun flushPendingMediaIfStale() {
            val item = pendingItem ?: return
            if (SystemClock.elapsedRealtime() - pendingAt < MEDIA_PAIR_TIMEOUT_MS) return
            deliverMedia(item, pendingThumb)
        }

        private fun deliverMedia(item: MediaItem, thumb: ByteArray?) {
            pendingItem = null
            pendingThumb = null
            AppLog.event(TAG, "收到对端发来的${if (item.isVideo) "视频" else "图片"}：${item.summary()}")
            listener?.onMediaFromPeer(item, thumb, peerDeviceId, peerName.ifEmpty { remoteHost })
        }

        private fun applyPeerInfo(payload: String) {
            var did = ""
            var name = ""
            payload.split(";").forEach { field ->
                val index = field.indexOf('=')
                if (index <= 0) return@forEach
                val key = field.substring(0, index)
                val value = field.substring(index + 1)
                when (key) {
                    "did" -> did = value
                    "name" -> name = value
                }
            }
            if (did.isNotEmpty()) peerDeviceId = did
            if (name.isNotEmpty()) peerName = name
        }

        /** 握手完成：登记到「对端 → 会话」表里，同一台设备只留一条规范连接。 */
        private fun resolve() {
            if (resolved || closed) return
            if (peerDeviceId.isEmpty()) {
                AppLog.error(TAG, "握手信息里没有 deviceId，关闭连接")
                close()
                return
            }
            if (peerDeviceId == deviceId) {
                AppLog.error(TAG, "对端 deviceId 与本机相同（连到了自己），关闭连接")
                close()
                return
            }
            resolved = true

            var toClose: Session? = null
            var busyWithOther = false
            synchronized(lock) {
                val current = activeByPeer[peerDeviceId]
                busyWithOther = !outgoing &&
                    activeByPeer.any { (id, session) -> id != peerDeviceId && !session.closed }
                when {
                    busyWithOther -> toClose = this
                    current == null || current.closed -> activeByPeer[peerDeviceId] = this
                    current === this -> Unit
                    isCanonical && !current.isCanonical -> {
                        activeByPeer[peerDeviceId] = this
                        toClose = current
                    }
                    else -> toClose = this
                }
            }

            toClose?.let { victim ->
                AppLog.info(
                    TAG,
                    if (busyWithOther) {
                        "一对一：已连着别的设备，拒绝 $label"
                    } else if (victim === this) {
                        "与同一台设备有两条连接，关闭这一条（$label）"
                    } else {
                        "与同一台设备有两条连接，保留规范连接，关闭 ${victim.label}"
                    },
                )
                victim.close()
            }

            if (activeByPeer[peerDeviceId] === this) {
                AppLog.event(TAG, "会话就绪：$label")
                updateConnectedState()
                onPeersChanged?.invoke()
            }
        }

        private fun updateConnectedState() {
            val names = connectedPeerNames()
            val summary = if (names.isEmpty()) {
                "监听中 :$port（等待对端）"
            } else {
                "已连接 ${names.size} 台：${names.joinToString("、")}"
            }
            StateHub.update {
                it.copy(
                    tcpState = summary,
                    connectedPeers = if (names.isEmpty()) "-" else names.joinToString("、"),
                    peerIp = remoteHost,
                    peerName = peerName.ifEmpty { "未知设备" },
                    lastError = "-",
                )
            }
        }

        /** 只入队，不做任何网络 I/O，所以主线程调用也安全。 */
        fun send(type: Int, payload: String): Boolean =
            sendBytes(type, payload.toByteArray(Charsets.UTF_8), payload)

        fun sendBytes(type: Int, payload: ByteArray, note: String? = null): Boolean {
            if (closed) return false
            if (!outQueue.offer(Proto.encode(type, payload))) {
                AppLog.error(TAG, "发送队列已满，丢弃 ${Proto.typeName(type)}（$label）")
                return false
            }
            if (!Proto.isHighFrequency(type)) {
                AppLog.tx(TAG, "${Proto.typeName(type)} -> $label: ${note ?: "${payload.size} 字节"}")
            }
            return true
        }

        /**
         * 大块数据（文件分片）用这个：队列满就等，不丢数据、也不做逐帧日志。
         * 只会在后台线程调用。
         */
        fun sendBytesBlocking(type: Int, payload: ByteArray, timeoutMs: Long): Boolean {
            if (closed) return false
            val bytes = Proto.encode(type, payload)
            return try {
                outQueue.offer(bytes, timeoutMs, TimeUnit.MILLISECONDS) && !closed
            } catch (e: InterruptedException) {
                false
            }
        }

        fun close() {
            if (closed) return
            closed = true
            writerThread?.interrupt()
            fileQueue.shutdownNow()
            runCatching { socket.close() }
        }

        private fun onClosed() {
            sessions.remove(this)
            if (peerDeviceId.isNotEmpty()) fileReceiver?.abortFrom(peerDeviceId)
            val wasActive = synchronized(lock) {
                val current = activeByPeer[peerDeviceId]
                if (current === this) {
                    activeByPeer.remove(peerDeviceId)
                    true
                } else {
                    false
                }
            }
            // 拨号结果统计：活得久算成功；秒断（被拒绝）就计一次失败并退避
            val key = dialKey
            if (key != null) {
                if (livedMs() < QUICK_DEATH_MS) {
                    noteDialFailure(key, remoteHost)
                } else {
                    noteDialSuccess(key)
                }
            }
            AppLog.info(TAG, "会话已结束（$label）")
            if (wasActive) {
                val names = connectedPeerNames()
                StateHub.update {
                    it.copy(
                        tcpState = if (names.isEmpty()) {
                            "连接已断开，正在重连…"
                        } else {
                            "已连接 ${names.size} 台：${names.joinToString("、")}"
                        },
                        connectedPeers = if (names.isEmpty()) "-" else names.joinToString("、"),
                        peerIp = "-",
                        peerName = "-",
                    )
                }
            }
            onPeersChanged?.invoke()
        }

        private fun helloPayload(): String =
            "did=$deviceId;name=$displayName;text=${Proto.HELLO_TEXT}"
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
