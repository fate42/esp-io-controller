package com.esp.controller

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 控制页 —— App 打开后的默认界面。
 *
 * v1.4（车库门钥匙）：
 *   1) 四个按键改为车库门语义：开门 / 关门 / 锁门 / 停止（不再暴露 GPIO 编号）。
 *   2) 移除「MQTT: xxx」技术文案，左下角只用一个圆点 + 已连接 / 断开 表示遥控器是否在线。
 *   3) 操作反馈改为自然语言：「开门按键已按下」。
 *   4) 界面按消费级 App 重做：大圆角按键、浅色卡片、图标化设置入口。
 */
class ControlActivity : AppCompatActivity() {

    private var mqtt: MqttManager? = null
    private lateinit var spDevice: Spinner
    private lateinit var tvDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var dotStatus: View
    private lateinit var tvState: TextView
    private lateinit var tvVersion: TextView
    private lateinit var gridButtons: GridLayout
    private lateinit var emptyView: LinearLayout

    private var devices: MutableList<Device> = mutableListOf()
    private var suppressSpinnerCallback = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastStateAt = 0L
    private var deviceOnline = false

    /** 四个按键的显示名，与 Constants.GPIO_PINS 顺序一一对应 */
    private val labels: Array<String>
        get() = arrayOf(
            getString(R.string.btn_open),
            getString(R.string.btn_close),
            getString(R.string.btn_lock),
            getString(R.string.btn_stop)
        )

    companion object {
        private const val OFFLINE_TIMEOUT_MS = 25_000L   // 固件心跳 10s，留 2 倍余量
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private const val FEEDBACK_MS = 3_000L           // 操作反馈停留时长，到点自动消失
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        DeviceStore.migrateLegacy(this)      // 老版本升级：把单个 deviceId 迁进设备列表

        spDevice = findViewById(R.id.spDevice)
        tvDevice = findViewById(R.id.tvDevice)
        tvStatus = findViewById(R.id.tvStatus)
        dotStatus = findViewById(R.id.dotStatus)
        tvState = findViewById(R.id.tvState)
        gridButtons = findViewById(R.id.gridButtons)
        emptyView = findViewById(R.id.emptyView)
        tvVersion = findViewById(R.id.tvVersion)
        tvVersion.text = appVersion()      // 右下角显示版本，便于确认手机上跑的是哪个构建

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.btnGoSettings).setOnClickListener { openSettings() }

        spDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerCallback) return
                val d = devices.getOrNull(pos) ?: return
                DeviceStore.setSelected(this@ControlActivity, d.id)
                bindDevice(d.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val names = labels
        val btnIds = intArrayOf(R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4)
        for (i in 0..3) {
            findViewById<Button>(btnIds[i]).apply {
                text = names[i]
                setOnClickListener {
                    val manager = mqtt
                    if (manager == null) {
                        toast(getString(R.string.go_add_device))
                        return@setOnClickListener
                    }
                    if (manager.sendPulse(Constants.GPIO_PINS[i])) {
                        showFeedback(getString(R.string.pressed_fmt, names[i]))
                    } else {
                        showFeedback(getString(R.string.press_failed))
                    }
                }
            }
        }

        handler.post(watchdog)
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** 重新读取设备列表并刷新下拉框（从设置页返回时会走这里） */
    private fun refreshDevices() {
        devices = DeviceStore.list(this)
        val names = devices.map { if (it.name == it.id) it.id else it.name }
        spDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        val selected = DeviceStore.getSelected(this)
        val idx = devices.indexOfFirst { it.id == selected }
        suppressSpinnerCallback = true
        if (idx >= 0) spDevice.setSelection(idx)
        suppressSpinnerCallback = false

        val hasDevice = devices.isNotEmpty()
        emptyView.visibility = if (hasDevice) View.GONE else View.VISIBLE
        gridButtons.visibility = if (hasDevice) View.VISIBLE else View.GONE
        spDevice.visibility = if (devices.size > 1) View.VISIBLE else View.GONE

        if (selected == null) {
            tvDevice.text = getString(R.string.no_device)
            tvState.text = ""
            mqtt?.disconnect()
            deviceOnline = false
            renderStatus(connected = false)
            return
        }
        bindDevice(selected)
    }

    /** 把 MQTT 连接到指定设备；已连接的是同一台（且密钥一致）就不重复连 */
    private fun bindDevice(deviceId: String) {
        val dev = DeviceStore.find(this, deviceId)
        val name = dev?.name ?: deviceId
        val key = dev?.key ?: ""
        tvDevice.text = if (name == deviceId) productIdLabel(deviceId) else name

        val manager = mqtt ?: MqttManager().also {
            it.onConnectionChange = { connected ->
                if (!connected) markOffline() else updateStatus()
            }
            it.onState = { s ->
                lastStateAt = System.currentTimeMillis()
                deviceOnline = !s.contains("offline")
                updateStatus()
            }
            it.onError = { msg -> /* 技术错误不再打扰用户，仅在状态灯上体现 */ }
            mqtt = it
        }

        if (manager.deviceId == deviceId && manager.deviceKey == key && manager.isConnected()) return
        lastStateAt = 0L
        deviceOnline = false
        tvStatus.text = getString(R.string.status_connecting)
        dotStatus.setBackgroundResource(R.drawable.dot_off)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        manager.switchTo(deviceId, key) {}
    }

    /** 设备ID 展示成「遥控器 · 后4位」，比整串 hex 更友好 */
    private fun productIdLabel(id: String): String =
        if (id.length > 4) "遥控器 · ${id.takeLast(4)}" else id

    /** 操作反馈若干秒后自动清空，避免"XX按键已按下"一直挂在界面上 */
    private val clearFeedback = Runnable { tvState.text = "" }

    private fun showFeedback(msg: String) {
        tvState.text = msg
        // 先撤掉上一次的定时，连按时以最后一次按下重新计时，不会被前一次提前清掉
        handler.removeCallbacks(clearFeedback)
        handler.postDelayed(clearFeedback, FEEDBACK_MS)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (lastStateAt != 0L && System.currentTimeMillis() - lastStateAt > OFFLINE_TIMEOUT_MS) {
                markOffline()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun markOffline() {
        deviceOnline = false
        updateStatus()
    }

    /** 只有「服务器已连上」且「遥控器心跳新鲜」才算已连接 */
    private fun updateStatus() {
        renderStatus(connected = mqtt?.isConnected() == true && deviceOnline)
    }

    private fun renderStatus(connected: Boolean) {
        tvStatus.text = getString(if (connected) R.string.status_on else R.string.status_off)
        tvStatus.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.status_on else R.color.text_secondary)
        )
        dotStatus.setBackgroundResource(if (connected) R.drawable.dot_on else R.drawable.dot_off)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(clearFeedback)
        mqtt?.release()
        mqtt = null
        super.onDestroy()
    }

    /** 右下角版本号，例如「v1.4.1」。用 PackageManager 读取，不依赖 BuildConfig 生成开关 */
    private fun appVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "v${info.versionName}"
    } catch (e: Exception) {
        ""
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
