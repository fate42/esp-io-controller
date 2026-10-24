package com.esp.controller

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 控制页：四个按钮，按下即向 ESP 发布脉冲命令；同时显示设备在线状态。
 *
 * v1.1 修复：
 *   1) mqtt 改为可空：原实现是 lateinit，而 onCreate 在 deviceId 为空时提前 return，
 *      随后 onDestroy 访问 mqtt 会抛 UninitializedPropertyAccessException 崩溃。
 *   2) 增加"心跳超时"判定：设备掉线时 broker 会代发 LWT offline，
 *      但若 App 侧没收到任何状态超过 OFFLINE_TIMEOUT_MS，也判为离线。
 */
class ControlActivity : AppCompatActivity() {

    private var mqtt: MqttManager? = null
    private lateinit var tvDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView

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

        tvDevice = findViewById(R.id.tvDevice)
        tvStatus = findViewById(R.id.tvStatus)
        tvState = findViewById(R.id.tvState)

        val deviceId = Prefs.getDeviceId(this)
        if (deviceId.isNullOrEmpty()) { gotoProvision(); return }
        tvDevice.text = "设备: $deviceId"

        val manager = MqttManager()
        mqtt = manager
        manager.onConnectionChange = { connected ->
            tvStatus.text = if (connected) "MQTT: 已连接 ✓" else "MQTT: 断开 ✗"
            if (!connected) markOffline()
        }
        manager.onState = { s ->
            lastStateAt = System.currentTimeMillis()
            deviceOnline = !s.contains("offline")
            tvState.text = "状态: $s"
            updateOnlineLabel()
        }
        manager.onError = { msg -> toast(msg) }
        manager.connect(deviceId) {}

        val labels = arrayOf("按钮1 (GPIO12)", "按钮2 (GPIO13)", "按钮3 (GPIO14)", "按钮4 (GPIO16)")
        val btnIds = intArrayOf(R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4)
        for (i in 0..3) {
            findViewById<Button>(btnIds[i]).apply {
                text = labels[i]
                setOnClickListener {
                    val ok = manager.sendPulse(Constants.GPIO_PINS[i])
                    tvState.text = if (ok) "已发送脉冲 -> GPIO${Constants.GPIO_PINS[i]}"
                                   else "发送失败：MQTT 未连接"
                }
            }
        }

        findViewById<Button>(R.id.btnReconfig).setOnClickListener {
            Prefs.clear(this)
            gotoProvision()
        }

        handler.post(watchdog)
    }

    /** 看门狗：长时间收不到设备状态就标记为离线 */
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
            !connected            -> "MQTT: 断开 ✗"
            deviceOnline          -> "MQTT: 已连接 ✓  设备在线"
            else                  -> "MQTT: 已连接 ✓  设备离线"
        }
    }

    private fun gotoProvision() {
        startActivity(Intent(this, ProvisionActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        mqtt?.release()          // 可空调用，未初始化时不会崩
        mqtt = null
        super.onDestroy()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
