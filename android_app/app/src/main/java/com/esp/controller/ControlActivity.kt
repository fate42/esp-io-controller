package com.esp.controller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 控制页：四个按钮，按下即向 ESP 发布脉冲命令。
 */
class ControlActivity : AppCompatActivity() {

    private lateinit var mqtt: MqttManager
    private lateinit var tvDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        tvDevice = findViewById(R.id.tvDevice)
        tvStatus = findViewById(R.id.tvStatus)
        tvState = findViewById(R.id.tvState)

        val deviceId = Prefs.getDeviceId(this)
        if (deviceId.isNullOrEmpty()) { gotoProvision(); return }
        tvDevice.text = "设备: $deviceId"

        mqtt = MqttManager()
        mqtt.onConnectionChange = { connected ->
            runOnUiThread {
                tvStatus.text = if (connected) "MQTT: 已连接 ✓" else "MQTT: 断开 ✗"
            }
        }
        mqtt.onState = { s -> runOnUiThread { tvState.text = "状态: $s" } }
        mqtt.connect(deviceId) {}

        val labels = arrayOf("按钮1 (GPIO12)", "按钮2 (GPIO13)", "按钮3 (GPIO14)", "按钮4 (GPIO16)")
        val btnIds = intArrayOf(R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4)
        for (i in 0..3) {
            findViewById<Button>(btnIds[i]).apply {
                text = labels[i]
                setOnClickListener {
                    mqtt.sendPulse(Constants.GPIO_PINS[i])
                    tvState.text = "已发送脉冲 -> GPIO${Constants.GPIO_PINS[i]}"
                }
            }
        }

        findViewById<Button>(R.id.btnReconfig).setOnClickListener {
            Prefs.clear(this)
            gotoProvision()
        }
    }

    private fun gotoProvision() {
        startActivity(Intent(this, ProvisionActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        mqtt.disconnect()
        super.onDestroy()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
