package com.opposhot.link.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.opposhot.link.R

/**
 * 前台服务：唯一一个能让网络/相册监听在后台活着的正规做法。
 *
 * 为什么不搞"保活黑科技"：ColorOS 会判定那类行为是流氓并更激进地杀。
 * 前台服务 + connectedDevice 类型是官方认可的理由（"与设备保持连接"），
 * 而且会在通知栏留一条安静的状态条，用户随时看得见、可以停掉。
 *
 * 低功耗：没有连上对端时，发现/轮询频率会自动降下来（见 UdpBeacon / MediaWatcher）。
 */
class LinkService : Service() {

    companion object {
        private const val TAG = "LinkService"
        const val CHANNEL_ID = "link_status"
        private const val NOTIFICATION_ID = 0x0A01

        fun start(context: Context) {
            val intent = Intent(context, LinkService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 后台启动前台服务被系统拒绝时（Android 12+ 有严格限制），退回普通启动
                AppLog.error(TAG, "启动前台服务被拒: ${e.javaClass.simpleName} ${e.message}")
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LinkService::class.java)) }
        }
    }

    private var statusListener: ((UiState) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ensureForeground("正在启动…")

        // 状态变化时刷新那条常驻通知。只在文案真的变了才重发：
        // 状态每几秒就会更新一次，每次都 notify 既费电，也会被系统限流、连带吞掉别的通知。
        var shownText = ""
        val listener: (UiState) -> Unit = { state ->
            val connected = state.tcpState.startsWith("已连接")
            // 「等待时保持通知」关掉 + 当前没连上 → 把常驻通知收掉，不占通知栏。
            // 保活不受影响：前台服务还在跑，只是没有可见的通知。
            // 一旦连上（或用户打开了这个开关）就立刻补回来。
            if (!connected && !DeviceIdentity.idleNotification(this)) {
                if (shownText.isNotEmpty()) {
                    shownText = ""
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            ?.cancel(NOTIFICATION_ID)
                    }
                    AppLog.info(TAG, "等待中，已收起常驻通知")
                }
            } else {
                val text = when {
                    connected -> {
                        val name = state.peerName
                        if (name.isNotBlank() && name != "-") "已连接 $name" else "已连接"
                    }
                    state.tcpState.contains("监听") || state.tcpState.contains("搜索") ->
                        "等待连接"
                    else -> state.tcpState
                }
                if (text != shownText) {
                    shownText = text
                    // 收起过之后要重新「占位」，否则 startForeground 的通知不会自己回来。
                    runCatching { ensureForeground(text) }
                }
            }
        }
        statusListener = listener
        StateHub.observe(listener)

        LinkController.start(this)
        AppLog.event(TAG, "前台服务已启动（保活）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        statusListener?.let { StateHub.removeObserver(it) }
        statusListener = null
        // 用户在设置里关掉常驻时，网络组件要继续在进程里跑；只有开关仍开着（被系统停掉）才一起停。
        if (DeviceIdentity.keepAlive(this)) LinkController.stop()
        AppLog.info(TAG, "前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 把自己标记成前台服务（带一条通知）。
     *
     * 叫 `startForeground` 之前必须已经发过通知，否则 Android 14+ 会抛
     * `ForegroundServiceDidNotStartInTimeException`；这里做两次调用是安全的。
     */
    private fun ensureForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台运行状态",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "保持与另一台设备的连接、监听新截图"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        // 点这条常驻通知 = 打开 App 界面。
        // 之前它不可点，用户想回来看"收到的东西"只能去桌面找图标，很别扭。
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.opposhot.link.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("OppoShot Link")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }
}
