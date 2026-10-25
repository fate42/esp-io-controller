package com.esp.controller

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 控制页 —— App 打开后的默认界面。
 *
 * v1.2：
 *   1) 不再一上来就跳配网页：即使没有设备也停留在本页，用空状态引导去「设置」添加。
 *   2) 顶部 Spinner 可在多台设备间切换（一个 App 可管多台 ESP）。
 *   3) 右上角「设置」进 SettingsActivity：配网新设备 / 扫码加入 / 二维码分享 / 删除重命名。
 *   4) 多部手机只要保存了同一个 deviceId，就能同时控制同一台 ESP（MQTT 主题天然支持）。
 */
class ControlActivity : AppCompatActivity() {

    private var mqtt: MqttManager? = null
    private lateinit var spDevice: Spinner
    private lateinit var tvDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView
    private lateinit var gridButtons: GridLayout
    private lateinit var emptyView: LinearLayout

    private var devices: MutableList<Device> = mutableListOf()
    private var suppressSpinnerCallback = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastStateAt = 0L
    private var deviceOnline = false

    companion object {
        private const val OFFLINE_TIMEOUT_MS = 25_000L   // 固件心跳 10s，留 2 倍余量
        private const val WATCHDOG_INTERVAL_MS = 3_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        DeviceStore.migrateLegacy(this)      // 老版本升级：把单个 deviceId 迁进设备列表

        spDevice = findViewById(R.id.spDevice)
        tvDevice = findViewById(R.id.tvDevice)
        tvStatus = findViewById(R.id.tvStatus)
        tvState = findViewById(R.id.tvState)
        gridButtons = findViewById(R.id.gridButtons)
        emptyView = findViewById(R.id.emptyView)

        findViewById<Button>(R.id.btnSettings).setOnClickListener { openSettings() }
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

        val labels = arrayOf("按钮1 (GPIO12)", "按钮2 (GPIO13)", "按钮3 (GPIO14)", "按钮4 (GPIO16)")
        val btnIds = intArrayOf(R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4)
        for (i in 0..3) {
            findViewById<Button>(btnIds[i]).apply {
                text = labels[i]
                setOnClickListener {
                    val manager = mqtt
                    if (manager == null) { toast("请先添加设备"); return@setOnClickListener }
                    val ok = manager.sendPulse(Constants.GPIO_PINS[i])
                    tvState.text = if (ok) "已发送脉冲 -> GPIO${Constants.GPIO_PINS[i]}"
                                   else "发送失败：MQTT 未连接"
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
        val names = devices.map { if (it.name == it.id) it.id else "${it.name}（${it.id}）" }
        spDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        val selected = DeviceStore.getSelected(this)
        val idx = devices.indexOfFirst { it.id == selected }
        suppressSpinnerCallback = true
        if (idx >= 0) spDevice.setSelection(idx)
        suppressSpinnerCallback = false

        val hasDevice = devices.isNotEmpty()
        emptyView.visibility = if (hasDevice) View.GONE else View.VISIBLE
        gridButtons.visibility = if (hasDevice) View.VISIBLE else View.GONE
        spDevice.visibility = if (hasDevice) View.VISIBLE else View.GONE

        if (selected == null) {
            tvDevice.text = getString(R.string.no_device)
            tvStatus.text = getString(R.string.mqtt_idle)
            tvState.text = getString(R.string.state_idle)
            mqtt?.disconnect()
            return
        }
        bindDevice(selected)
    }

    /** 把 MQTT 连接到指定设备；已连接的是同一台就不重复连 */
    private fun bindDevice(deviceId: String) {
        val name = DeviceStore.find(this, deviceId)?.name ?: deviceId
        tvDevice.text = if (name == deviceId) "设备: $deviceId" else "设备: $name（$deviceId）"

        val manager = mqtt ?: MqttManager().also {
            it.onConnectionChange = { connected ->
                tvStatus.text = if (connected) "MQTT: 已连接 ✓" else "MQTT: 断开 ✗"
                if (!connected) markOffline()
            }
            it.onState = { s ->
                lastStateAt = System.currentTimeMillis()
                deviceOnline = !s.contains("offline")
                tvState.text = "状态: $s"
                updateOnlineLabel()
            }
            it.onError = { msg -> toast(msg) }
            mqtt = it
        }

        if (manager.deviceId == deviceId && manager.isConnected()) return
        lastStateAt = 0L
        deviceOnline = false
        tvStatus.text = "MQTT: 连接中…"
        manager.switchTo(deviceId) {}
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
        updateOnlineLabel()
    }

    private fun updateOnlineLabel() {
        val connected = mqtt?.isConnected() == true
        tvStatus.text = when {
            !connected   -> "MQTT: 断开 ✗"
            deviceOnline -> "MQTT: 已连接 ✓  设备在线"
            else         -> "MQTT: 已连接 ✓  设备离线"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        mqtt?.release()
        mqtt = null
        super.onDestroy()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
