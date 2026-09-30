package com.opposhot.link.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.opposhot.link.R
import com.opposhot.link.core.AppLog
import com.opposhot.link.core.DeviceIdentity
import com.opposhot.link.core.LinkController
import com.opposhot.link.core.Permissions
import com.opposhot.link.core.ReceivedStore
import com.opposhot.link.core.StateHub
import com.opposhot.link.core.UiState

/** 设置页：权限 / 后台 / 诊断（连日志也放这里，主界面保持干净）。 */
class SettingsFragment : Fragment() {

    private lateinit var tvPermMedia: TextView
    private lateinit var tvPermNotify: TextView
    private lateinit var tvPermVisible: TextView
    private lateinit var swKeepAlive: MaterialSwitch
    private lateinit var swIdleNotification: MaterialSwitch
    private lateinit var swLog: MaterialSwitch
    private lateinit var tvDiag: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvAbout: TextView

    // 必须在 Fragment 创建阶段注册；点按钮时才注册会直接抛 IllegalStateException 崩溃。
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { LinkController.refreshPermissions() }

    private val listener: (UiState) -> Unit = { render(it) }
    private val logListener: (AppLog.Entry) -> Unit = { entry ->
        if (swLog.isChecked) {
            if (tvLog.lineCount > 500) tvLog.text = ""
            tvLog.append("${entry.time} [${entry.level}] ${entry.tag}: ${entry.message}\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvPermMedia = view.findViewById(R.id.tvPermMedia)
        tvPermNotify = view.findViewById(R.id.tvPermNotify)
        tvPermVisible = view.findViewById(R.id.tvPermVisible)
        swKeepAlive = view.findViewById(R.id.swKeepAlive)
        swIdleNotification = view.findViewById(R.id.swIdleNotification)
        swLog = view.findViewById(R.id.swLog)
        tvDiag = view.findViewById(R.id.tvDiag)
        tvLog = view.findViewById(R.id.tvLog)
        logScroll = view.findViewById(R.id.logScroll)
        tvAbout = view.findViewById(R.id.tvAbout)

        view.findViewById<MaterialButton>(R.id.btnGrant).setOnClickListener { grantPermissions() }
        view.findViewById<MaterialButton>(R.id.btnAppSettings).setOnClickListener { openAppSettings() }
        view.findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { disableBatteryOptimization() }
        view.findViewById<MaterialButton>(R.id.btnRestart).setOnClickListener { LinkController.restart(requireContext()) }
        view.findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
            ReceivedStore.clear(requireContext())
            AppLog.event("Settings", "已清空历史")
        }

        swKeepAlive.setOnCheckedChangeListener { _, checked ->
            if (checked != DeviceIdentity.keepAlive(requireContext())) {
                LinkController.setKeepAlive(requireContext(), checked)
            }
        }
        swIdleNotification.setOnCheckedChangeListener { _, checked ->
            if (checked != DeviceIdentity.idleNotification(requireContext())) {
                DeviceIdentity.setIdleNotification(requireContext(), checked)
                AppLog.event("Settings", "等待时保持通知 = $checked")
                // 立刻生效：连着的话通知本来就在；没连的话会补上或收掉。
                LinkController.refreshPermissions()
            }
        }
        swLog.setOnCheckedChangeListener { _, checked ->
            logScroll.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                tvLog.text = AppLog.snapshot().joinToString("") {
                    "${it.time} [${it.level}] ${it.tag}: ${it.message}\n"
                }
            }
        }

        tvAbout.text = "OppoShot Link · OPPO 平板和手机之间传图片/视频/截图\n" +
            "不经过服务器，只走系统「跨设备互联」建立的直连链路。"

        AppLog.addListener(logListener)
        StateHub.observe(listener)
    }

    private fun grantPermissions() {
        val missing = Permissions.toRequest().filterNot { Permissions.granted(requireContext(), it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null),
                )
            )
        }
    }

    private fun disableBatteryOptimization() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", requireContext().packageName, null))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun render(state: UiState) {
        tvPermMedia.text = "照片和视频：" + state.mediaPermission
        tvPermNotify.text = "通知：" + state.notificationPermission
        tvPermVisible.text = if (state.visibleImages < 0) {
            "相册可见：正在检查…"
        } else {
            "相册可见：${state.visibleImages} 张"
        }

        swKeepAlive.setOnCheckedChangeListener(null)
        swKeepAlive.isChecked = DeviceIdentity.keepAlive(requireContext())
        swKeepAlive.setOnCheckedChangeListener { _, checked ->
            if (checked != DeviceIdentity.keepAlive(requireContext())) {
                LinkController.setKeepAlive(requireContext(), checked)
            }
        }
        swIdleNotification.setOnCheckedChangeListener(null)
        swIdleNotification.isChecked = DeviceIdentity.idleNotification(requireContext())

        tvDiag.text = buildString {
            append("本机：").append(state.deviceName).append("（").append(state.deviceId).append("）\n")
            append("接口：").append(state.primaryInterface).append("  ").append(state.localIp).append("\n")
            append("OPPO 直连链路：").append(if (state.p2pLink) "有" else "无").append("\n")
            append("监听：").append(state.listenState).append("\n")
            append("连接：").append(state.tcpState).append("\n")
            append("传输：").append(state.transferState).append("\n")
            append("错误：").append(state.lastError)
        }
    }

    override fun onDestroyView() {
        AppLog.removeListener(logListener)
        StateHub.removeObserver(listener)
        super.onDestroyView()
    }
}
