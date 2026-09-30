package com.opposhot.link

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.opposhot.link.core.AppLog
import com.opposhot.link.core.DeviceIdentity
import com.opposhot.link.core.LinkController
import com.opposhot.link.core.LinkService
import com.opposhot.link.core.Permissions
import com.opposhot.link.core.StateHub
import com.opposhot.link.core.UiState
import com.opposhot.link.ui.DevicePageFragment
import com.opposhot.link.ui.SettingsFragment

/**
 * I2「一台设备一页」：没有列表页，左右滑动在设备之间切换。
 * 最后一页固定是「设置」。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var pager: ViewPager2
    private var mediator: TabLayoutMediator? = null

    private var deviceIds: List<String> = emptyList()
    private var deviceNames: List<String> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { LinkController.refreshPermissions() }

    private val stateListener: (UiState) -> Unit = { state ->
        val online = state.connectedPeers
        val connected = online != "-" && online.isNotEmpty()
        toolbar.subtitle = when {
            connected -> "已连接 $online"
            state.scanning -> "正在寻找设备…"
            else -> "没有连接的设备"
        }

        val ids = state.devices.map { it.deviceId }.filter { it.isNotEmpty() }
        val names = state.devices.map { it.name.ifBlank { it.host } }
        if (ids != deviceIds) {
            deviceIds = ids
            deviceNames = names
            rebuildPages()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        pager = findViewById(R.id.pager)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_scan -> {
                    if (StateHub.current().scanning) {
                        LinkController.stopScan("用户手动停止")
                    } else {
                        LinkController.startScan()
                    }
                    true
                }
                R.id.action_settings -> {
                    pager.setCurrentItem(deviceIds.size, true)
                    true
                }
                else -> false
            }
        }

        rebuildPages()
        StateHub.observe(stateListener)

        if (!LinkController.isRunning()) {
            if (DeviceIdentity.keepAlive(this)) LinkService.start(this) else LinkController.start(this)
        }

        val missing = Permissions.toRequest().filterNot { Permissions.granted(this, it) }
        if (missing.isNotEmpty() && savedInstanceState == null) {
            AppLog.event("MainActivity", "启动时请求权限")
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun rebuildPages() {
        mediator?.detach()
        pager.adapter = PagerAdapter()
        val labels = if (deviceIds.isEmpty()) {
            listOf("状态", "设置")
        } else {
            deviceNames + "设置"
        }
        mediator = TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = labels.getOrElse(position) { "" }
        }.also { it.attach() }
    }

    private inner class PagerAdapter : FragmentStateAdapter(this) {

        override fun getItemCount(): Int = maxOf(deviceIds.size, 1) + 1

        override fun createFragment(position: Int): Fragment = when {
            deviceIds.isEmpty() && position == 0 -> NoDeviceFragment()
            position < deviceIds.size -> DevicePageFragment.newInstance(deviceIds[position])
            else -> SettingsFragment()
        }
    }

    /** 一台设备都没发现时的第一页。 */
    class NoDeviceFragment : Fragment() {
        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: android.view.ViewGroup?,
            savedInstanceState: Bundle?,
        ): android.view.View {
            val context = requireContext()
            val box = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }
            box.addView(
                android.widget.TextView(context).apply {
                    text = "还没有发现设备\n\n确认两台设备都打开了系统的「跨设备互联」，\n并且对方也打开了这个 App"
                    gravity = android.view.Gravity.CENTER
                    textSize = 15f
                }
            )
            box.addView(
                com.google.android.material.button.MaterialButton(context).apply {
                    text = "开始查找"
                    setOnClickListener { LinkController.startScan() }
                },
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 32 },
            )
            return box
        }
    }

    override fun onResume() {
        super.onResume()
        LinkController.refreshPermissions()
        stateListener(StateHub.current())
    }

    override fun onDestroy() {
        StateHub.removeObserver(stateListener)
        mediator?.detach()
        super.onDestroy()
    }
}
