package com.opposhot.link.core

import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.opposhot.link.discovery.PeerCandidate
import com.opposhot.link.discovery.PeerRegistry
import com.opposhot.link.discovery.UdpBeacon
import com.opposhot.link.net.FileDone
import com.opposhot.link.net.MediaItem
import com.opposhot.link.net.NetInfo
import com.opposhot.link.net.PeerNode
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * **精简版：只做 OPPO 平板 ↔ OPPO 手机，一对一。**
 *
 * 为什么砍掉这么多：
 *   实测在校园网 / 企业网这类多 AP 环境里，同一 SSID 的两台设备经常互相看不见
 *   （AP 隔离 / NAC 终端隔离），局域网发现不但没用，还会露出一堆连不上的"设备"。
 *   而 OPPO 自己的「跨设备互联」拉起来的 p2p0（192.168.49.x）完全不经过路由器，
 *   是唯一稳定可用的通路 —— 所以只认这一条。
 *
 * 启动的东西（每台设备完全一样）：
 *   - PeerNode：监听 45555 + 主动连接那一台对端
 *   - UdpBeacon：**只在 p2p0 网段内**广播发现 / 应答
 *   - MediaWatcher：监听本机新保存的图片/视频
 *
 * 用户交互的两条主线：
 *   1. **连接**：打开 App 自动找 OPPO 直连对端；连上一台就不再自动连第二台（一对一）。
 *   2. **互通内容**：图片 / 视频 / 截图 / 仅接收，四个开关。
 */
object LinkController {

    const val TCP_PORT = 45555

    private const val TAG = "Link"
    private const val ACCEPT_RETRY_MS = 12_000L

    /** 打开 App 最多主动找多久。超过就停，等用户点【重新查找】。 */
    private const val SCAN_TIMEOUT_MS = 5 * 60 * 1000L
    private const val SCAN_WATCHDOG_MS = 2_000L

    @Volatile private var running = false

    private var node: PeerNode? = null
    private var beacon: UdpBeacon? = null
    private var watcher: MediaWatcher? = null
    private var interfaceWatcher: Thread? = null
    private var scanWatchdog: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastLockCheckAt = 0L
    private var appContext: Context? = null

    @Volatile private var selfDeviceId: String = "-"

    /** 主动发现当前是否在跑。 */
    @Volatile private var discoveryActive = false
    /** 上一次检查时 p2p0 是否存在。用来判断「互联刚刚恢复」。 */
    @Volatile private var lastSeenP2p = false

    /**
     * 用户本次明确同意连接的对端（deviceId 或 host）。
     * 只在本次运行期间有效 —— 想长期连就开「自动连接」，那才会写进 SharedPreferences。
     */
    private val approvedHosts: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val approvedIds: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 媒体 id → 发来它的那台设备。多对多时点通知只向那台要原图。 */
    private val mediaOrigin = ConcurrentHashMap<String, String>()

    /** 只发截图 = 打开后，别的 App 保存的照片/视频不会通知对端。（保留旧开关，默认关） */
    @Volatile var onlyScreenshots: Boolean = false
        set(value) {
            field = value
            StateHub.update { it.copy(onlyScreenshots = value) }
            AppLog.event(
                TAG,
                if (value) "筛选：只发送截图" else "筛选：发送所有新图片/视频",
            )
        }

    fun start(context: Context) {
        stopAll()
        val app = context.applicationContext
        appContext = app
        PeerRegistry.clear()
        approvedIds.clear()
        approvedHosts.clear()
        mediaOrigin.clear()

        val deviceId = DeviceIdentity.deviceId(app)
        val displayName = DeviceIdentity.displayName()
        selfDeviceId = deviceId
        StateHub.update {
            UiState(
                deviceId = deviceId,
                deviceName = displayName,
                tcpState = "启动中…",
                listenState = "启动中…",
                onlyScreenshots = onlyScreenshots,
                keepAlive = DeviceIdentity.keepAlive(app),
                scanning = true,
                scanState = "正在寻找设备…",
            )
        }
        AppLog.event(TAG, "本机名称 = $displayName；deviceId = $deviceId（对等模式，无需选角色）")

        acquireMulticastLock(app)
        startInterfaceWatcher()
        refreshInterfaces()

        node = PeerNode(TCP_PORT, deviceId, displayName, app) { host -> isDirectLink(host) }.also { peer ->
            peer.fileReceiver = FileReceiver(
                context = app,
                sendDone = { peerId, id, ok, note -> peer.sendFileDoneTo(peerId, id, ok, note) },
                onProgress = { id, percent ->
                    StateHub.update { it.copy(transferState = "正在保存… $percent%") }
                    Notifier.showTransferring(app, id, percent)
                },
                onSaved = { id, path ->
                    StateHub.update {
                        it.copy(
                            transferState = "已保存到相册：$path",
                            mediaSaved = it.mediaSaved + 1,
                        )
                    }
                    ReceivedStore.markSaved(app, id)
                    Notifier.showResult(app, id, true, path)
                },
                onFailed = { id, reason ->
                    StateHub.update {
                        it.copy(transferState = "保存失败：$reason", lastError = reason)
                    }
                    Notifier.showResult(app, id, false, reason)
                },
            )
            // 内容开关：每台对端独立决定互通哪些内容（接收方向）
            peer.policyGate = { did, kind -> PeerSettings.of(app, did).allows(kind) }
            // 发送方向：多看一层「仅接收」。打开了就照常收，但一律不往外发。
            peer.sendGate = { did, _ ->
                val receiveOnly = PeerSettings.of(app, did).receiveOnly
                if (receiveOnly) AppLog.info(TAG, "与 $did 设为「仅接收」，不往外发")
                !receiveOnly
            }
            // 连接必须经过用户同意，或者该设备开了「自动连接」
            peer.mayDial = { did, host ->
                val allowed = PeerSettings.autoConnect(app, did) ||
                    approvedIds.contains(did) ||
                    approvedHosts.contains(host)
                // 一对一：已经连着一台时，不再自动连第二台。
                // 要换设备，请在界面上点另一台的【连接】（会先把上一台断开）。
                val busyWithOther = allowed && peer.hasOtherActivePeer(did)
                if (busyWithOther) {
                    AppLog.info(TAG, "一对一模式：已连着别的设备，暂不自动连 ${did.take(6)}")
                }
                allowed && !busyWithOther
            }
            peer.onPeersChanged = { publishDevices() }
            peer.listener = object : PeerNode.Listener {
                override fun onMediaFromPeer(item: MediaItem, thumb: ByteArray?, fromId: String, fromName: String) {
                    deliverMediaToUi(item, thumb, fromId, fromName)
                }

                override fun onTransferProgress(id: String, percent: Int) {
                    StateHub.update { it.copy(transferState = "正在发送原图… $percent%") }
                }

                override fun onTransferFailed(id: String, reason: String) {
                    StateHub.update {
                        it.copy(transferState = "发送失败：$reason", lastError = reason)
                    }
                }

                override fun onRemoteFileDone(id: String, ok: Boolean, note: String, reason: String) {
                    val context = appContext
                    acceptingIds.remove(id)
                    if (ok) {
                        StateHub.update { it.copy(transferState = "对端已保存：$note") }
                        return@onRemoteFileDone
                    }

                    // 对方那边已经拿不出这张了 —— 留着这条历史只会让人反复点一个永远失败的东西。
                    // 分两种：gone = 相册里真的没了；unreadable = 还在但打不开
                    // （安卓「部分照片授权」时就是这种，App 只能看到被勾选的那几张）。
                    // 两种都清理，但文案说清楚原因，不然用户会以为 App 坏了。
                    if (context != null &&
                        (reason == FileDone.REASON_GONE || reason == FileDone.REASON_UNREADABLE)
                    ) {
                        val why = if (reason == FileDone.REASON_GONE) {
                            "对方已删除这张照片"
                        } else {
                            "对方未授权访问这张照片"
                        }
                        if (ReceivedStore.remove(context, id)) {
                            // 只是被动清理一条已经没用的记录，不值得占通知栏 —— 用 Toast 轻轻带一句。
                            // 顺手把「正在保存」那条常驻通知收掉，否则它会挂到超时才消失。
                            Notifier.cancel(context, id)
                            toast(context, "$why，记录已移除")
                            AppLog.event(TAG, "对端取不到此文件（$reason），删除本地历史记录：$id")
                            StateHub.update { it.copy(transferState = "$why，记录已移除") }
                            publishDevices()
                            return@onRemoteFileDone
                        }
                    }

                    // 一定要把「正在保存」那条常驻通知收掉，否则它会挂到 60 秒超时才消失。
                    context?.let { Notifier.showResult(it, id, false, "对端保存失败：$note") }
                    StateHub.update { it.copy(transferState = "对端保存失败：$note") }
                }
            }
            peer.start()
        }
        beacon = UdpBeacon(deviceId, displayName, TCP_PORT, { host -> isDirectLink(host) }) { onPeerFound(it) }.also { b ->
            b.onBlocked = { refreshMulticastLock() }
            b.start()
        }

        Notifier.ensureChannel(app)
        watcher = MediaWatcher(
            context = app,
            onlyScreenshots = { onlyScreenshots },
            isConnected = { node?.isConnected == true },
            onNewMedia = { item, thumb -> onLocalMediaNew(item, thumb) },
            onStatus = { text -> StateHub.update { it.copy(watchState = text) } },
            onVisibility = { count -> StateHub.update { it.copy(visibleImages = count) } },
        ).also { it.start() }

        running = true
        lastNewPeerAt = System.currentTimeMillis()
        startScanWatchdog()
        startScan()
        publishDevices()
        refreshPermissions()
    }

    // ---------------------------------------------------------------- 查找 / 连接

    /** 开始主动查找设备（打开 App 时、点【重新查找】时、以及互联重新出现时都走这里）。 */
    fun startScan() {
        if (appContext == null) return
        discoveryActive = true
        beacon?.setScanning(true)
        // 恢复主动拨号（stopScan 时关掉的）
        node?.dialEnabled = true
        lastNewPeerAt = System.currentTimeMillis()
        AppLog.event(TAG, "开始查找设备")
        StateHub.update { it.copy(scanning = true, scanState = "正在寻找设备…") }
    }

    /**
     * 停止主动查找。
     *
     * 会同时停掉「广播寻找」和「主动拨号」——
     * 只停广播是不够的：候选地址会一直留在 PeerRegistry 里，
     * 拨号循环每 1.5 秒就会重试一次，两台设备分开时表现为连了又断、断了又连。
     *
     * 仍然保持「被动」：TCP 端口继续监听、UDP 继续应答别人，
     * 所以对方主动连过来依然能通。互联重新出现时会自动恢复（见 refreshInterfaces）。
     */
    fun stopScan(reason: String) {
        if (!discoveryActive) return
        discoveryActive = false
        beacon?.setScanning(false)
        node?.dialEnabled = false
        AppLog.event(TAG, "停止查找：$reason")
        StateHub.update { it.copy(scanning = false, scanState = reason) }
    }

    /** 找满 5 分钟还没发现新设备就自动停。 */
    private fun startScanWatchdog() {
        scanWatchdog = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(SCAN_WATCHDOG_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (!discoveryActive) continue
                val quiet = System.currentTimeMillis() - lastNewPeerAt
                if (quiet >= SCAN_TIMEOUT_MS) {
                    stopScan("找了 5 分钟还是没找到设备，已停止。确认对方也打开了 App，或点【重新查找】")
                }
            }
        }, "opposhot-scan-watchdog").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** 用户点【连接】：本次运行内允许主动连这台设备。 */
    fun requestConnect(deviceId: String, host: String) {
        // 一对一：用户选了另一台 → 先把原来的断开，只连这一台
        val previous = runCatching { node?.connectedPeers().orEmpty() }.getOrDefault(emptyList())
            .filter { it.first != deviceId }
        previous.forEach { (oldId, oldName, _) ->
            AppLog.event(TAG, "一对一：改连 ${deviceId.take(6)}，先断开 $oldName")
            node?.disconnect(oldId)
            approvedIds.remove(oldId)
        }

        if (deviceId.isNotEmpty()) approvedIds.add(deviceId)
        if (host.isNotEmpty()) approvedHosts.add(host)
        AppLog.event(TAG, "用户同意连接：${deviceId.ifEmpty { host }} @ $host")
        // 可能是因为没在扫描才连不上，顺手把查找打开
        if (!discoveryActive) startScan()
        publishDevices()
    }

    /** 点【断开】：断开与这台设备的连接，并取消本次的连接许可。 */
    fun disconnectPeer(deviceId: String, host: String) {
        approvedIds.remove(deviceId)
        approvedHosts.remove(host)
        node?.disconnect(deviceId)
        AppLog.event(TAG, "用户断开与 ${deviceId.ifEmpty { host }} 的连接")
        publishDevices()
    }

    fun setAutoConnect(deviceId: String, enabled: Boolean) {
        val context = appContext ?: return
        PeerSettings.setAutoConnect(context, deviceId, enabled)
        if (enabled) approvedIds.add(deviceId)
        publishDevices()
    }

    /** 每台设备四个内容开关。关掉的类型：既不发过去，收到也丢掉。 */
    fun setContentPolicy(deviceId: String, policy: ContentPolicy) {
        val context = appContext ?: return
        PeerSettings.set(context, deviceId, policy)
        publishDevices()
    }

    /**
     * 「忘记」一台设备：从列表里移除，并清掉它的设置。
     * 常用场景：换了一台机器 / 想让 App 重新握手拿它的名字。
     * 注意：**只删本地记录，不发任何消息给对端**（对端仍会通过发现机制再出现）。
     */
    fun forgetPeer(deviceId: String, host: String) {
        PeerRegistry.forget(deviceId, host)
        if (deviceId.isNotEmpty()) {
            appContext?.let { PeerSettings.clear(it, deviceId) }
            node?.disconnect(deviceId)
        }
        approvedIds.remove(deviceId)
        approvedHosts.remove(host)
        AppLog.event(TAG, "已忘记设备 ${deviceId.ifEmpty { host }}")
        publishDevices()
    }

    // ---------------------------------------------------------------- 媒体

    private fun onLocalMediaNew(item: MediaItem, thumb: ByteArray?) {
        AppLog.event(
            TAG,
            "本机新${if (item.isVideo) "视频" else "图片"}：${item.summary()} → 推给对端",
        )
        StateHub.update { it.copy(mediaSent = it.mediaSent + 1) }
        node?.sendMediaEvent(item, thumb)
    }

    private fun deliverMediaToUi(item: MediaItem, thumb: ByteArray?, fromId: String, fromName: String) {
        val name = fromName.ifBlank { "未知设备" }
        StateHub.update { it.copy(mediaReceived = it.mediaReceived + 1) }
        val context = appContext ?: return
        // 记下是谁发来的：点通知/点列表时只向那台要原图
        if (fromId.isNotEmpty()) mediaOrigin[item.id] = fromId
        // 存进「最近收到」：通知超时/被划掉后还能回来保存
        ReceivedStore.add(context, fromId, name, item, thumb)
        Notifier.showOffer(context, item, thumb)
    }

    // ---------------------------------------------------------------- 权限 / 杂项

    /** 权限授予/撤销后由界面调用：刷新状态显示，并立刻再扫一次。 */
    fun refreshPermissions() {
        val context = appContext ?: return
        val mediaText = Permissions.mediaStateText(context)
        val notifyText = Permissions.notificationStateText(context)
        val usable = Permissions.mediaGranted(context)
        StateHub.update {
            it.copy(
                mediaPermission = mediaText,
                notificationPermission = notifyText,
                watchState = if (usable) "监听中" else "等待媒体读取权限",
            )
        }
        if (usable) watcher?.rescanNow()
    }

    /** 生成一张测试图片写进相册，用来端到端验证监听链路。 */
    fun saveTestImage() {
        val context = appContext ?: return
        if (!Permissions.mediaGranted(context)) {
            AppLog.error(TAG, "还没有媒体读取权限，无法写入测试图片")
            return
        }
        DemoMedia.saveTestImage(context)
    }

    /**
     * 后台常驻开关（用户可选）。关着的时候，App 被系统收走就彻底收不到东西；
     * 打开后会起一个前台服务把连接和相册监听留在后台（通知栏会多一条安静的状态条）。
     */
    fun setKeepAlive(context: Context, enabled: Boolean) {
        DeviceIdentity.setKeepAlive(context, enabled)
        StateHub.update { it.copy(keepAlive = enabled) }
        if (enabled) {
            AppLog.event(TAG, "打开后台常驻")
            LinkService.start(context)
        } else {
            AppLog.event(TAG, "关闭后台常驻")
            // 网络组件继续在当前进程里跑（只是不再常驻）。
            // 服务的 onDestroy 看到开关已关，不会再把它们停掉。
            LinkService.stop(context)
            if (!isRunning()) start(context.applicationContext)
        }
    }

    /** 前台服务的 onDestroy 会调 stop()，所以这里要能判断"是不是已经在跑"。 */
    fun isRunning(): Boolean = node != null

    /**
     * 点通知 / 点「最近收到」→ 请求原图。
     * 多对多时只向「当初发来这条媒体的那台设备」要；那台不在线时再退回广播询问。
     */
    fun acceptMedia(mediaId: String) {
        AppLog.event(TAG, "请求原图：$mediaId")
        val context = appContext ?: return
        // 同一个文件已经在请求中就别再起线程，否则连点会起好几个
        if (!acceptingIds.add(mediaId)) {
            AppLog.info(TAG, "这个文件已经在请求中，忽略重复点击：$mediaId")
            return
        }
        Notifier.showTransferring(context, mediaId, 0)
        // 进程重启后 mediaOrigin 是空的，退回「最近收到」里记下的来源
        val originId = mediaOrigin[mediaId]
            ?: ReceivedStore.list(context).firstOrNull { it.id == mediaId }?.deviceId?.takeIf { it.isNotEmpty() }
        Thread({
            val deadline = System.currentTimeMillis() + ACCEPT_RETRY_MS
            var ok = false
            var announced = false
            while (System.currentTimeMillis() < deadline) {
                val sent = if (originId != null) {
                    node?.acceptTo(originId, mediaId) == true
                } else {
                    node?.accept(mediaId) == true
                }
                if (sent) {
                    ok = true
                    break
                }
                if (!announced) {
                    announced = true
                    AppLog.info(TAG, "还没连上发来这条的设备，先重新找一下")
                    startScan()
                }
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
            if (!ok) {
                AppLog.error(TAG, "请求原图失败：暂时连不上对端")
                StateHub.update {
                    it.copy(
                        transferState = "请求失败：连不上对端",
                        lastError = "请求原图失败：连不上对端",
                    )
                }
                Notifier.showResult(context, mediaId, false, "连不上对方，请确认两台设备都打开了 App")
            }
            acceptingIds.remove(mediaId)
        }, "opposhot-accept").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** 用户点「取消」：本地停止 + 通知对端停止。 */
    fun cancelTransfer(mediaId: String) {
        AppLog.event(TAG, "取消传输：$mediaId")
        node?.cancelTransfer(mediaId)
        appContext?.let { Notifier.showCancelled(it, mediaId) }
        StateHub.update { it.copy(transferState = "已取消") }
    }

    /** 通知被点而 App 可能没在跑：保证网络组件已启动。 */
    fun ensureStarted(context: Context) {
        if (node == null) {
            AppLog.event(TAG, "从通知唤醒：启动网络组件")
            start(context)
        }
    }

    fun restart(context: Context) {
        AppLog.event(TAG, "重新开始")
        start(context)
    }

    fun stop() {
        AppLog.event(TAG, "停止全部网络组件")
        stopAll()
        running = false
        StateHub.update {
            it.copy(
                tcpState = "已停止",
                listenState = "已停止",
                peerIp = "-",
                peerName = "-",
                devices = emptyList(),
                scanning = false,
                scanState = "已停止",
            )
        }
    }

    // ---------------------------------------------------------------- 发现到的设备

    /**
     * 轻量提示。
     * 只用于「App 顺手做了一件小事」这类不需要用户操作的反馈（比如自动清理了失效记录），
     * 不占用通知栏。真正需要用户处理的事情才走 Notifier。
     */
    fun toast(context: Context, message: String) {
        AppLog.event(TAG, "Toast：$message")
        runCatching {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 正在请求原图的 media id：防止连点 / 重入时起多个请求线程。 */
    private val acceptingIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var lastNewPeerAt = System.currentTimeMillis()

    private fun onPeerFound(candidate: PeerCandidate) {
        // 过滤掉「自己」：广播会被自己收到（地址是本机某个接口的 IP）。
        if (candidate.deviceId != "unknown" && candidate.deviceId == selfDeviceId) {
            AppLog.info(TAG, "忽略自己（${candidate.host}:${candidate.port}，来源 ${candidate.source}）")
            return
        }
        val localAddresses = StateHub.current().interfaces.map { it.address }.toSet()
        if (candidate.host in localAddresses) {
            AppLog.info(TAG, "忽略本机地址（${candidate.host}:${candidate.port}，来源 ${candidate.source}）")
            return
        }

        val marked = candidate.copy(preferred = isDirectLink(candidate.host))
        val isNew = PeerRegistry.add(marked)
        if (isNew) {
            lastNewPeerAt = System.currentTimeMillis()
            AppLog.event(
                TAG,
                "发现对端：${marked.name} @ ${marked.host}:${marked.port}（${marked.source}" +
                    if (marked.preferred) "，直连链路）" else "）",
            )
        }
        publishDevices()
    }

    /**
     * 把「发现到的」和「已连接的」合并成一张设备列表给界面。
     *
     * 排序：已连接的在前（按名字），其余按「直连优先 → 最近看到」排。
     */
    private fun publishDevices() {
        val context = appContext ?: return
        val connected = runCatching { node?.connectedPeers().orEmpty() }.getOrDefault(emptyList())
        val entries = LinkedHashMap<String, DeviceEntry>()

        // 1) 已连接的（用握手拿到的真实 deviceId）
        connected.forEach { (did, name, host) ->
            entries["id:$did"] = DeviceEntry(
                deviceId = did,
                name = name.ifBlank { "未知设备" },
                host = host,
                connected = true,
                directLink = isDirectLink(host),
                autoConnect = PeerSettings.autoConnect(context, did),
                policy = PeerSettings.of(context, did),
            )
        }

        // 2) 发现到的
        PeerRegistry.list().forEach { c ->
            val did = c.deviceId.takeIf { it.isNotEmpty() && it != "unknown" }
            if (did != null) {
                val existing = entries["id:$did"]
                if (existing != null) {
                    // 同一台设备的另一个地址：只在「发现了更快的链路」时替换
                    if (existing.host != c.host && (c.preferred || !existing.directLink)) {
                        entries["id:$did"] = existing.copy(
                            name = c.name.ifBlank { existing.name },
                            host = c.host,
                            directLink = c.preferred,
                        )
                    }
                } else {
                    entries["id:$did"] = DeviceEntry(
                        deviceId = did,
                        name = c.name.ifBlank { did.take(6) },
                        host = c.host,
                        connected = false,
                        directLink = c.preferred,
                        autoConnect = PeerSettings.autoConnect(context, did),
                        policy = PeerSettings.of(context, did),
                    )
                }
            } else {
                // 只知道地址（子网兜底扫描）：按地址列一张卡，连上后才有 deviceId
                val key = "host:${c.host}"
                if (entries.values.none { it.host == c.host } && !entries.containsKey(key)) {
                    entries[key] = DeviceEntry(
                        deviceId = "",
                        name = "未知设备",
                        host = c.host,
                        connected = false,
                        directLink = c.preferred,
                        autoConnect = false,
                        policy = ContentPolicy(),
                    )
                }
            }
        }

        val ordered = entries.values.sortedWith(
            compareByDescending<DeviceEntry> { it.connected }
                .thenBy { it.name }
                .thenBy { it.host },
        )
        StateHub.update { it.copy(devices = ordered, peers = PeerRegistry.list()) }
    }

    /** host 是否落在本机某个 p2p 接口的网段里（= 走直连链路）。 */
    private fun isDirectLink(host: String): Boolean {
        val interfaces = StateHub.current().interfaces
        return interfaces.any { iface ->
            iface.looksLikeP2p && NetInfo.sameSubnet(host, iface.address, iface.prefixLength)
        }
    }

    private fun stopAll() {
        runCatching { node?.stop() }
        runCatching { beacon?.stop() }
        runCatching { watcher?.stop() }
        node = null
        beacon = null
        watcher = null
        discoveryActive = false

        interfaceWatcher?.interrupt()
        interfaceWatcher = null
        scanWatchdog?.interrupt()
        scanWatchdog = null

        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /** 定期刷新接口列表：OPPO 互联可能在 App 启动之后才把 p2p0 拉起来。 */
    private fun startInterfaceWatcher() {
        interfaceWatcher = Thread({
            while (!Thread.currentThread().isInterrupted) {
                refreshInterfaces()
                try {
                    Thread.sleep(3_000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "opposhot-ifaces").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun refreshInterfaces() {
        val interfaces = NetInfo.scan()
        val primary = NetInfo.primary(interfaces)
        val p2p = interfaces.any { it.looksLikeP2p }

        // OPPO 互联重新建立（p2p0 又出现了）→ 自动恢复查找和拨号。
        // 这是「两台设备分开 → 5 分钟后停止重试 → 拿到一起又能自动连上」的关键：
        // 没有这段的话，5 分钟一过就永远停在停止状态，用户必须手动点【重新查找】。
        if (p2p && !lastSeenP2p) {
            if (!discoveryActive) {
                AppLog.event(TAG, "检测到 OPPO 互联恢复，自动重新查找设备")
                startScan()
            }
        }
        lastSeenP2p = p2p

        // 每 15 秒确认一次组播锁还在。ColorOS / OxygenOS 会在 Wi-Fi 链路重建时把锁收走，
        // 一旦丢了，广播发送会直接 EPERM，设备就再也发现不了别人。
        val context = appContext
        if (context != null) {
            val now = System.currentTimeMillis()
            if (now - lastLockCheckAt > 15_000L) {
                lastLockCheckAt = now
                // 每 15 秒无条件重放一次组播锁。
                // 巡检只能发现「锁没了」，发现不了「锁还在但内核过滤已经回来了」，
                // 所以直接重放最省事（开销极小）。
                acquireMulticastLock(context)
            }
        }

        StateHub.update {
            it.copy(
                interfaces = interfaces,
                primaryInterface = primary?.name ?: "未检测到可用接口",
                localIp = primary?.cidr ?: "-",
                p2pLink = p2p,
            )
        }
    }

    /**
     * 获取组播锁。安卓在没有这把锁的时候**禁止往广播地址发包**（sendto 直接返回 EPERM），
     * 也会把入站广播/mDNS 过滤掉。实测 ColorOS / OxygenOS 会在 Wi-Fi 链路重建时
     * 悄悄把锁收走（锁对象还在，但 isHeld 变成 false），所以要反复检查并补拿。
     */
    private fun acquireMulticastLock(context: Context) {
        try {
            val wifiManager = context.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
            // 先无条件释放旧的：有些机型（OxygenOS 实测）锁对象还是 isHeld=true，
            // 但内核里的组播过滤已经恢复成「过滤」，这时必须 release 再 acquire 才生效。
            multicastLock?.let { runCatching { it.release() } }
            multicastLock = null
            val lock = wifiManager.createMulticastLock("opposhot-multicast")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            AppLog.info(TAG, "已获取 MulticastLock（降低 ColorOS 过滤广播/mDNS 的概率）")
        } catch (e: Exception) {
            AppLog.error(TAG, "MulticastLock 获取失败: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** 广播被 EPERM 拒绝时的补救：强制重放一遍锁。 */
    fun refreshMulticastLock() {
        val context = appContext ?: return
        AppLog.event(TAG, "广播被拒，强制重放组播锁")
        acquireMulticastLock(context)
    }
}
