package com.esp.controller

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 基于纯 Java 版 Paho 客户端的 MQTT 封装（不依赖旧 support 库）。
 * - 连接公共 broker
 * - 订阅 esp8266/<deviceId>/state 接收状态（含设备 LWT 遗嘱消息 offline）
 * - 向 esp8266/<deviceId>/cmd 发布 {"pin":X} 触发 500ms 脉冲
 *
 * v1.1 修复：disconnect() 不再关闭线程池（原实现关掉后同一实例无法再次 connect），
 * 线程池的关闭拆到 release()，只在 Activity 真正销毁时调用。
 */
class MqttManager {

    private var client: MqttClient? = null
    private var executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var deviceId: String = ""
    var onState: ((String) -> Unit)? = null
    var onConnectionChange: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun isConnected(): Boolean = client?.isConnected ?: false

    fun connect(deviceId: String, onConnected: () -> Unit) {
        this.deviceId = deviceId
        if (executor.isShutdown) executor = Executors.newSingleThreadExecutor()
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
                    maxInflight = 20
                }
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        mainHandler.post {
                            onConnectionChange?.invoke(true)
                            if (!reconnect) onConnected()
                        }
                    }
                    override fun connectionLost(cause: Throwable?) {
                        mainHandler.post {
                            onConnectionChange?.invoke(false)
                            onError?.invoke("连接丢失：${cause?.message ?: "未知原因"}")
                        }
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
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    onConnectionChange?.invoke(false)
                    onError?.invoke(e.message ?: e.toString())
                }
            }
        }
    }

    /** 发布 {"pin":X}，QoS1 保证至少送达一次 */
    fun sendPulse(pin: Int): Boolean {
        val c = client
        if (c == null || !c.isConnected) {
            onError?.invoke("MQTT 未连接，脉冲未发出")
            return false
        }
        val topic = "esp8266/$deviceId/cmd"
        val payload = "{\"pin\":$pin}"
        executor.execute {
            try {
                c.publish(topic, payload.toByteArray(), 1, false)
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onError?.invoke("发布失败：${e.message}") }
            }
        }
        return true
    }

    /** 断开 MQTT 连接，但保留线程池，之后还能 connect() */
    fun disconnect() {
        try { client?.disconnect() } catch (e: Exception) { /* ignore */ }
        client = null
    }

    /** Activity 销毁时调用：彻底释放线程资源 */
    fun release() {
        disconnect()
        executor.shutdown()
    }
}
