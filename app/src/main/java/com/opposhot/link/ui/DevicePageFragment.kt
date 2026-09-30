package com.opposhot.link.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.opposhot.link.R
import com.opposhot.link.core.DeviceEntry
import com.opposhot.link.core.LinkController
import com.opposhot.link.core.ReceivedStore
import com.opposhot.link.core.StateHub
import com.opposhot.link.core.UiState

/**
 * I2：一台设备一页。
 * 左边/右边滑动就在设备之间切换；这一页里放的是「这台设备」的全部设置和它发来的东西。
 */
class DevicePageFragment : Fragment() {

    companion object {
        private const val ARG_ID = "deviceId"

        fun newInstance(deviceId: String): DevicePageFragment = DevicePageFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, deviceId) }
        }
    }

    private val deviceId: String get() = arguments?.getString(ARG_ID).orEmpty()

    private lateinit var tvName: TextView
    private lateinit var tvState: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var swImage: MaterialSwitch
    private lateinit var swVideo: MaterialSwitch
    private lateinit var swShot: MaterialSwitch
    private lateinit var swRecvOnly: MaterialSwitch
    private lateinit var tvModeHint: TextView
    private lateinit var swAuto: MaterialSwitch
    private lateinit var tvNoHistory: TextView
    private lateinit var history: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var scroll: android.widget.ScrollView

    private var lastHistorySignature = "<none>"

    private val listener: (UiState) -> Unit = { state ->
        val entry = state.devices.firstOrNull { it.deviceId == deviceId }
        render(entry)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_device_page, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvName = view.findViewById(R.id.tvName)
        tvState = view.findViewById(R.id.tvState)
        btnConnect = view.findViewById(R.id.btnConnect)
        swImage = view.findViewById(R.id.swImage)
        swVideo = view.findViewById(R.id.swVideo)
        swShot = view.findViewById(R.id.swShot)
        swRecvOnly = view.findViewById(R.id.swRecvOnly)
        tvModeHint = view.findViewById(R.id.tvModeHint)
        swAuto = view.findViewById(R.id.swAuto)
        tvNoHistory = view.findViewById(R.id.tvNoHistory)
        history = view.findViewById(R.id.history)
        scroll = view.findViewById(R.id.scroll)

        // 平板横屏时 ScrollView 里的控件会抢焦点，把页面自动滚下去、把顶部设备卡挡住。
        // 明确让这一页永远从顶部开始显示。
        scroll.post { scroll.scrollTo(0, 0) }

        historyAdapter = HistoryAdapter { mediaId -> LinkController.acceptMedia(mediaId) }
        history.layoutManager = LinearLayoutManager(requireContext())
        history.adapter = historyAdapter

        StateHub.observe(listener)
        renderHistory()
    }

    private fun render(entry: DeviceEntry?) {
        if (entry == null) {
            tvName.text = "设备已离开"
            tvState.text = "下拉或点右上角重新查找"
            btnConnect.visibility = View.GONE
            return
        }

        tvName.text = entry.name.ifBlank { entry.host }
        tvState.text = (if (entry.connected) "已连接" else "未连接") + " · " + entry.host
        btnConnect.visibility = View.VISIBLE
        btnConnect.text = if (entry.connected) "断开" else "连接"
        btnConnect.setOnClickListener {
            if (entry.connected) {
                LinkController.disconnectPeer(entry.deviceId, entry.host)
            } else {
                LinkController.requestConnect(entry.deviceId, entry.host)
            }
        }

        val p = entry.policy
        // 「仅接收」打开时，下面三个类型开关就没什么意义了（反正不往外发），
        // 直接置灰，让用户一眼看出当前是「只收不发」。
        val sending = !p.receiveOnly
        swImage.isEnabled = sending
        swVideo.isEnabled = sending
        swShot.isEnabled = sending
        listOf(swImage, swVideo, swShot).forEach { it.alpha = if (sending) 1f else 0.4f }
        tvModeHint.text = if (p.receiveOnly) {
            "对方发来的照常保存；你这边的新照片不会发过去"
        } else {
            "你这边新拍的照片会自动发给对方"
        }

        bindSwitch(swImage, p.images) { LinkController.setContentPolicy(entry.deviceId, p.copy(images = it)) }
        bindSwitch(swVideo, p.videos) { LinkController.setContentPolicy(entry.deviceId, p.copy(videos = it)) }
        bindSwitch(swShot, p.screenshots) { LinkController.setContentPolicy(entry.deviceId, p.copy(screenshots = it)) }
        bindSwitch(swRecvOnly, p.receiveOnly) {
            LinkController.setContentPolicy(entry.deviceId, p.copy(receiveOnly = it))
        }
        bindSwitch(swAuto, entry.autoConnect) { LinkController.setAutoConnect(entry.deviceId, it) }

        renderHistory()
    }

    private fun bindSwitch(view: MaterialSwitch, checked: Boolean, onChange: (Boolean) -> Unit) {
        view.setOnCheckedChangeListener(null)
        view.isChecked = checked
        view.setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun renderHistory() {
        val name = tvName.text?.toString().orEmpty()
        // 按 deviceId 归属；老记录可能没有 deviceId，才退回按名字（两台同型号的设备名字会一样）
        val entries = ReceivedStore.list(requireContext()).filter {
            if (it.deviceId.isNotEmpty()) it.deviceId == deviceId else it.deviceName == name
        }
        val signature = entries.joinToString("|") { "${it.id}:${it.saved}" }
        if (signature == lastHistorySignature) return
        lastHistorySignature = signature
        historyAdapter.submit(entries)
        tvNoHistory.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        StateHub.removeObserver(listener)
        super.onDestroyView()
    }

    private class HistoryAdapter(private val onSave: (String) -> Unit) :
        RecyclerView.Adapter<HistoryHolder>() {

        private var items: List<ReceivedStore.Entry> = emptyList()

        fun submit(list: List<ReceivedStore.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return HistoryHolder(view, onSave)
        }

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) = holder.bind(items[position])
    }

    private class HistoryHolder(view: View, private val onSave: (String) -> Unit) :
        RecyclerView.ViewHolder(view) {

        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        private val tvSub: TextView = view.findViewById(R.id.tvSub)
        private val tvAction: TextView = view.findViewById(R.id.tvAction)

        fun bind(entry: ReceivedStore.Entry) {
            val context = itemView.context
            tvTitle.text = (if (entry.isVideo) "视频" else "图片") + " · " + entry.timeText()
            tvSub.text = if (entry.saved) "已保存到相册" else "${entry.sizeBytes / 1024} KB · 点一下保存"
            tvAction.text = if (entry.saved) "已保存" else "保存"

            val file = ReceivedStore.thumbFile(context, entry)
            if (file != null) {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                thumb.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, options))
            } else {
                thumb.setImageDrawable(null)
            }

            itemView.isClickable = !entry.saved
            itemView.setOnClickListener { if (!entry.saved) onSave(entry.id) }
        }
    }
}