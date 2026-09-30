package com.opposhot.link

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.opposhot.link.core.AppLog

/**
 * 用 Material You（Android 12+）从系统壁纸取色：
 * 这样 App 的配色会和用户手机本身的主题一致，不再是"一个外来 App 的样子"。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }.onFailure {
            AppLog.error("App", "动态取色不可用（不影响使用）: ${it.message}")
        }
    }
}
