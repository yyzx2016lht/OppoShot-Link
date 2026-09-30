package com.opposhot.link.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 点通知时触发（不进界面）：
 *   ACTION_SAVE   → 告诉对端「我要这个文件」，对端才开始传原图
 *   ACTION_CANCEL → 取消正在进行的传输
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mediaId = intent.getStringExtra(Notifier.EXTRA_ID) ?: return
        val appContext = context.applicationContext

        when (intent.action) {
            Notifier.ACTION_SAVE -> {
                AppLog.event(TAG, "用户点了通知，请求原图：$mediaId")
                LinkController.ensureStarted(appContext)
                LinkController.acceptMedia(mediaId)
            }

            Notifier.ACTION_CANCEL -> {
                AppLog.event(TAG, "用户点了「取消」：$mediaId")
                LinkController.cancelTransfer(mediaId)
            }
        }
    }

    private companion object {
        const val TAG = "ActionReceiver"
    }
}
