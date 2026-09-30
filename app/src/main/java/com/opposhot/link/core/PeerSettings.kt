package com.opposhot.link.core

import android.content.Context

/**
 * 「和哪台设备、互通哪些内容」的设置。
 *
 * 只保留真正在用的三类：图片 / 视频 / 截图。
 * 外加一个 receiveOnly「仅接收」：打开了就不再往外发，只收不送。
 */
data class ContentPolicy(
    val images: Boolean = true,
    val videos: Boolean = true,
    val screenshots: Boolean = true,
    /** 仅接收：照常接收对方发来的，但不主动把自己的相册发过去。 */
    val receiveOnly: Boolean = false,
) {
    /** kind: image / video / screenshot */
    fun allows(kind: String): Boolean = when (kind) {
        "image" -> images
        "video" -> videos
        "screenshot" -> screenshots
        else -> false
    }

    fun toStorage(): String = listOf(
        if (images) "1" else "0",
        if (videos) "1" else "0",
        if (screenshots) "1" else "0",
        if (receiveOnly) "1" else "0",
    ).joinToString(",")

    companion object {
        fun fromStorage(raw: String?): ContentPolicy {
            if (raw == null) return ContentPolicy()
            val p = raw.split(",")
            if (p.size < 3) return ContentPolicy()
            return ContentPolicy(
                images = p[0] == "1",
                videos = p[1] == "1",
                screenshots = p[2] == "1",
                // 第 4 位是新增的「仅接收」，老配置里没有 → 默认关，保持原来的收发行为
                receiveOnly = p.getOrNull(3) == "1",
            )
        }
    }
}

/** 每台对端设备的内容开关（按 deviceId 记住，重启不丢）。 */
object PeerSettings {

    private const val PREFS = "opposhot_peer_policy"
    private const val KEY_PREFIX = "policy_"
    private const val KEY_AUTO_PREFIX = "auto_"

    fun of(context: Context, deviceId: String): ContentPolicy {
        if (deviceId.isEmpty() || deviceId == "unknown") return ContentPolicy()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + deviceId, null)
        return ContentPolicy.fromStorage(raw)
    }

    fun set(context: Context, deviceId: String, policy: ContentPolicy) {
        if (deviceId.isEmpty() || deviceId == "unknown") return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + deviceId, policy.toStorage()).apply()
        AppLog.event(
            "PeerSettings",
            "更新与 $deviceId 的互通设置：图片=${policy.images} 视频=${policy.videos} " +
                "截图=${policy.screenshots} 仅接收=${policy.receiveOnly}",
        )
    }

    /** 是否「发现就自动连接」。默认关：连接必须经过用户同意。 */
    fun autoConnect(context: Context, deviceId: String): Boolean {
        if (deviceId.isEmpty() || deviceId == "unknown") return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PREFIX + deviceId, false)
    }

    fun setAutoConnect(context: Context, deviceId: String, enabled: Boolean) {
        if (deviceId.isEmpty() || deviceId == "unknown") return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_PREFIX + deviceId, enabled).apply()
        AppLog.event("PeerSettings", "与 $deviceId 的自动连接 = $enabled")
    }

    /** 忘记这台设备：连同互通设置一起清掉。 */
    fun clear(context: Context, deviceId: String) {
        if (deviceId.isEmpty() || deviceId == "unknown") return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + deviceId)
            .remove(KEY_AUTO_PREFIX + deviceId)
            .apply()
        AppLog.event("PeerSettings", "已清除与 $deviceId 的所有设置")
    }
}
