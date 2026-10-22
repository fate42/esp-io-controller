package com.esp.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 基于纯 Java 版 Paho 客户端的 MQTT 封装（AndroidX 安全）。
 * - 连接到公共 broker
 * - 订阅 esp8266/<deviceId>/state 接收状态
 * - 向 esp8266/<deviceId>/cmd 发布 {"pin":X} 触发脉冲
 */
class MqttManager {

    private var client: MqttClient? = null
    var deviceId: String = ""
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var onState: ((String) -> Unit)? = null
    var onConnectionChange: ((Boolean) -> Unit)? = null

    fun isConnected(): Boolean = client?.isConnected ?: false

    fun connect(deviceId: String, onConnected: () -> Unit) {
        this.deviceId = deviceId
        executor.execute {
            try {
                val serverUri = "tcp://${Constants.MQTT_BROKER}:${Constants.MQTT_PORT}"
                val clientId = "app_${deviceId}_${UUID.randomUUID()}"
                val c = MqttClient(serverUri, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                }
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        mainHandler.post { onConnectionChange?.invoke(false) }
                    }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val s = message?.toString() ?: return
                        mainHandler.post { onState?.invoke(s) }
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                c.connect(options)
                client = c
                c.subscribe("esp8266/$deviceId/state", 1)
                mainHandler.post {
                    onConnectionChange?.invoke(true)
                    onConnected()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onConnectionChange?.invoke(false) }
            }
        }
    }

    fun sendPulse(pin: Int) {
        val topic = "esp8266/$deviceId/cmd"
        val payload = "{\"pin\":$pin}"
        executor.execute {
            try {
                client?.publish(topic, payload.toByteArray(), 1, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        try { client?.disconnect() } catch (e: Exception) { /* ignore */ }
        client = null
        executor.shutdown()
    }
}
