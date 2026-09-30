package com.opposhot.link.core

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * 设备身份：安装时生成一个稳定的随机 ID（卸载重装会变）。
 * Phase 1 用它来决定「谁先发起的连接说了算」，以及发现时区分设备。
 */
object DeviceIdentity {

    private const val PREFS = "opposhot_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_IDLE_NOTIFICATION = "idle_notification"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    /** 后台常驻开关（用户可选）。 */
    fun keepAlive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_ALIVE, false)

    fun setKeepAlive(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    /**
     * 「等待时保持通知」。
     *
     * 默认 **false**：找不到设备的 5 分钟里不占通知栏。
     * 打开后则一直显示一条安静的常驻通知（文案会变成「等待连接」）。
     *
     * 关掉它不影响保活本身 —— 保活是靠前台服务 + 自启动白名单，
     * 这只决定「要不要在通知栏留一条」。
     */
    fun idleNotification(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_IDLE_NOTIFICATION, false)

    fun setIdleNotification(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IDLE_NOTIFICATION, enabled).apply()
    }

    fun displayName(): String = Build.MODEL ?: "Android"
}
