package com.opposhot.link.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 媒体读取 + 通知权限。
 *
 * 重要（踩过的坑）：我们**故意不申请** `READ_MEDIA_VISUAL_USER_SELECTED`。
 * 一旦申请了这个权限，系统就会把「只选择部分照片/视频」当成一个可选项，
 * 于是 App 很容易被放进「部分访问」状态 —— 那种状态下看不到别的 App 新存的文件，
 * 我们的核心功能会静默失效。我们要求的是「允许全部」。
 */
object Permissions {

    /** 需要向用户申请的运行时权限。 */
    fun toRequest(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun mediaGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * 「部分访问」的检测。
     *
     * 注意：**不能靠 READ_MEDIA_VISUAL_USER_SELECTED 是否被授予来判断**。
     * 实测（Android 14 / 16）即使 App 不声明该权限、用户选的是「允许全部」，
     * 系统也会把它授予。可靠的办法是功能自检（见 MediaWatcher.probeVisibility），
     * 这里只判断「有没有媒体读取权限」。
     */
    fun hasFullMediaAccess(context: Context): Boolean = mediaGranted(context)

    fun mediaStateText(context: Context): String {
        if (!mediaGranted(context)) return "未授权（点【请求权限】）"
        return "已授权（可读取照片和视频）"
    }

    /**
     * 「修权限」用的请求列表。
     *
     * 关键：必须带上 READ_MEDIA_VISUAL_USER_SELECTED。
     * 实测 Android 14/16：如果 App 之前被放进「只授权部分照片」状态（相册里一张都看不到），
     * 只请求 READ_MEDIA_IMAGES 是**不会弹窗**的；带上这个权限一起请求，
     * 系统才重新弹「允许全部 / 选择照片」的对话框。
     */
    fun mediaRepairRequest(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                list.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    fun notificationStateText(context: Context): String =
        if (Notifier.canNotify(context)) "已授权" else "未授权（通知发不出来）"

    fun notificationCanPost(context: Context): Boolean = Notifier.canNotify(context)
}
