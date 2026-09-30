package com.opposhot.link.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opposhot.link.R
import com.opposhot.link.net.MediaItem

/**
 * 通知：
 *  - 只显示缩略图 + 一行「点击即可保存」，不显示文件名、不放多余按钮；
 *  - 点整条通知 = 保存（走 ActionReceiver，不打开界面）；
 *  - 最多同时保留 10 条，超了就把最旧的顶掉；
 *  - 2 分钟没点就自动消失（表示不需要）。
 */
object Notifier {

    private const val TAG = "Notifier"
    const val CHANNEL_ID = "media_events"
    const val ACTION_SAVE = "com.opposhot.link.action.SAVE"
    const val ACTION_CANCEL = "com.opposhot.link.action.CANCEL"
    const val EXTRA_ID = "media_id"

    private const val MAX_ALIVE = 10
    private const val OFFER_TIMEOUT_MS = 2 * 60 * 1000L
    private const val SAVED_TIMEOUT_MS = 5_000L
    /** 进度通知的兜底超时：即使对端一直没响应，也不会永远挂在通知栏。 */
    private const val TRANSFER_TIMEOUT_MS = 60 * 1000L
    /** 剪贴板提示只是"知会一声"，很快就消失。 */

    /**
     * 通知分组。
     * Android 默认只有「同一个 App 的通知超过 4 条」才会自动折叠；
     * 显式设置同一个 group key + 一条 group summary，2 条以上就能折叠成一组。
     */
    private const val GROUP_KEY = "com.opposhot.link.MEDIA"
    private const val SUMMARY_ID = 0x0A55

    /** 当前挂着的通知（旧的在前），用于最多保留 10 条。 */
    private val alive = ArrayDeque<Int>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "对方保存的图片/视频",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "另一台设备新保存了图片或视频时提醒你，点一下就能保存过来"
                setShowBadge(true)
            }
        )
        AppLog.info(TAG, "已创建通知渠道：$CHANNEL_ID")
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun idOf(mediaId: String): Int = mediaId.hashCode()

    /** 收到新的媒体：弹一条「点击即可保存」。 */
    fun showOffer(context: Context, item: MediaItem, thumb: ByteArray?) {
        if (!canNotify(context)) {
            AppLog.error(TAG, "没有通知权限，通知发不出去")
            return
        }

        val intent = Intent(context, ActionReceiver::class.java)
            .setAction(ACTION_SAVE)
            .putExtra(EXTRA_ID, item.id)
        val pending = PendingIntent.getBroadcast(
            context,
            idOf(item.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val bitmap = thumb?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (item.isVideo) "收到一个新视频" else "收到一张新图片")
            .setContentText("点击即可保存")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setTimeoutAfter(OFFER_TIMEOUT_MS)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false)

        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setBigContentTitle(if (item.isVideo) "收到一个新视频" else "收到一张新图片")
                    .setSummaryText("点击即可保存")
            )
        }

        val notificationId = idOf(item.id)
        post(context, notificationId, builder.build())
        remember(context, notificationId)
        postSummary(context)
        AppLog.event(TAG, "已弹通知（点击即可保存）：${item.displayName}")
    }

    /** 用户点了通知：转一下「正在保存」，并给一个「取消」按钮。 */
    fun showTransferring(context: Context, mediaId: String, percent: Int) {
        val cancelIntent = Intent(context, ActionReceiver::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_ID, mediaId)
        val cancelPending = PendingIntent.getBroadcast(
            context,
            idOf(mediaId) xor 0x55,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (percent > 0) "正在保存… $percent%" else "正在保存…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, percent, percent <= 0)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setTimeoutAfter(TRANSFER_TIMEOUT_MS)
            .addAction(0, "取消", cancelPending)
        post(context, idOf(mediaId), builder.build())
    }

    /** 用户点了「取消」。 */
    fun showCancelled(context: Context, mediaId: String) {
        showResult(context, mediaId, false, "已取消")
    }

    /** 传完（或失败）：短提示，5 秒后自动消失。 */
    fun showResult(context: Context, mediaId: String, ok: Boolean, note: String) {
        synchronized(alive) { alive.remove(idOf(mediaId)) }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (ok) "已保存到相册" else "保存失败")
            .setContentText(note)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(SAVED_TIMEOUT_MS)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
        post(context, idOf(mediaId), builder.build())
        postSummary(context)
    }


    fun cancel(context: Context, mediaId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(idOf(mediaId)) }
        synchronized(alive) { alive.remove(idOf(mediaId)) }
        postSummary(context)
    }

    /** 组摘要：把同组的通知折叠成「N 条新的图片/视频」。 */
    private fun postSummary(context: Context) {
        val count = synchronized(alive) { alive.size }
        val manager = NotificationManagerCompat.from(context)
        if (count <= 1) {
            runCatching { manager.cancel(SUMMARY_ID) }
            return
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("$count 条新的图片/视频")
            .setContentText("展开后点任意一条即可保存")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setTimeoutAfter(OFFER_TIMEOUT_MS)
            .setSilent(true)
            .build()
        runCatching { manager.notify(SUMMARY_ID, summary) }
    }

    private fun remember(context: Context, notificationId: Int) {
        val overflow = synchronized(alive) {
            alive.remove(notificationId)
            alive.addLast(notificationId)
            val removed = mutableListOf<Int>()
            while (alive.size > MAX_ALIVE) removed.add(alive.removeFirst())
            removed
        }
        overflow.forEach {
            AppLog.info(TAG, "通知超过 $MAX_ALIVE 条，顶掉最旧的一条")
            runCatching { NotificationManagerCompat.from(context).cancel(it) }
        }
        postSummary(context)
    }

    private fun post(context: Context, notificationId: Int, notification: android.app.Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }.onFailure {
            AppLog.error(TAG, "弹通知失败: ${it.javaClass.simpleName} ${it.message}")
        }
    }
}
