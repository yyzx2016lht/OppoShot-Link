package com.opposhot.link.core

import android.os.Handler
import android.os.Looper
import com.opposhot.link.discovery.PeerCandidate
import com.opposhot.link.net.IfaceInfo

/** 设备列表里的一项（含每台设备自己的开关）。 */
data class DeviceEntry(
    val deviceId: String,
    val name: String,
    val host: String,
    val connected: Boolean,
    /** 是否走 OPPO 的直连链路（p2p0），比绕同一个 Wi-Fi 快得多。 */
    val directLink: Boolean,
    val autoConnect: Boolean,
    val policy: ContentPolicy,
) {
    /** 只有握手拿到真实 deviceId 之后，才能记住它的开关设置。 */
    val hasIdentity: Boolean get() = deviceId.isNotEmpty()
}

/** UI 上要展示的全部状态。任何线程都可以通过 StateHub 更新，主线程收到渲染回调。 */
data class UiState(
    val deviceId: String = "-",
    val deviceName: String = "-",
    val interfaces: List<IfaceInfo> = emptyList(),
    val primaryInterface: String = "-",
    val localIp: String = "-",
    val listenState: String = "未启动",
    /** 发现到的候选（底层原始列表）。 */
    val peers: List<PeerCandidate> = emptyList(),
    /** 给界面用的设备列表（合并了「发现到的」和「已连接的」）。 */
    val devices: List<DeviceEntry> = emptyList(),
    /** 主动查找的状态文案。 */
    val scanState: String = "未开始",
    /** 是否正在主动查找。 */
    val scanning: Boolean = false,
    val peerName: String = "-",
    val peerIp: String = "-",
    val connectedPeers: String = "-",
    val tcpState: String = "未启动",
    val lastError: String = "-",
    // 媒体 / 权限
    val watchState: String = "未启动",
    val mediaPermission: String = "-",
    val notificationPermission: String = "-",
    val onlyScreenshots: Boolean = false,
    val mediaSent: Int = 0,
    val mediaReceived: Int = 0,
    val mediaSaved: Int = 0,
    /** 相册里能看到的图片数；-1 表示还没测过。0 = 实际上没有访问权。 */
    val visibleImages: Int = -1,
    /** OPPO 互联的直连链路（p2p0）当前是否存在。 */
    val p2pLink: Boolean = false,
    /** 后台常驻开关状态。 */
    val keepAlive: Boolean = false,
    /** 原图收发的状态文案。 */
    val transferState: String = "-",
) {
    /** 有没有真的连着对端。判断依据是 connectedPeers，不看 tcpState 的文案。 */
    val isConnected: Boolean
        get() = connectedPeers != "-" && connectedPeers.isNotEmpty()

    /**
     * 状态一句话。**界面副标题和通知栏共用这一份**，不要各写各的 ——
     * 之前两边各判各的，出现过「界面说正在寻找设备、通知还显示已连接」。
     */
    fun statusLine(): String = when {
        isConnected -> "已连接 $connectedPeers"
        scanning -> "正在寻找设备…"
        !p2pLink -> "未连接 · 请打开跨设备互联"
        else -> "等待连接"
    }
}

object StateHub {

    private var state = UiState()
    private val listeners = mutableListOf<(UiState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun current(): UiState = synchronized(this) { state }

    fun update(transform: (UiState) -> UiState) {
        val next = synchronized(this) {
            val previous = state
            state = transform(state)
            // 没变就不通知：接口列表每 3 秒刷一次，大多数时候什么都没变
            if (state == previous) return
            state
        }
        mainHandler.post {
            val snapshot = synchronized(this) { listeners.toList() }
            snapshot.forEach { it(next) }
        }
    }

    fun observe(listener: (UiState) -> Unit) {
        synchronized(this) { listeners.add(listener) }
        listener(current())
    }

    fun removeObserver(listener: (UiState) -> Unit) = synchronized(this) {
        listeners.remove(listener)
    }
}
