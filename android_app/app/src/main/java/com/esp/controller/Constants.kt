package com.esp.controller

object Constants {
    /** ESP 配网热点前缀；设备ID 与其一致 */
    const val AP_PREFIX = "ESP_"
    /** 配网热点密码（与固件保持一致） */
    const val AP_PASSWORD = "espconfig"
    /** ESP 配网 HTTP 地址（热点默认网关 192.168.4.1） */
    const val CONFIG_URL = "http://192.168.4.1/config"
    /** 公共 MQTT broker（零成本） */
    const val MQTT_BROKER = "broker.emqx.io"
    const val MQTT_PORT = 1883
    /** MQTT 主题前缀；与固件 TOPIC_PREFIX 保持一致（v1.3 起由 esp8266/ 改为 io/） */
    const val MQTT_TOPIC_PREFIX = "io/"
    /** 四个受控 GPIO，顺序对应按钮 1~4 */
    val GPIO_PINS = intArrayOf(12, 13, 14, 16)
    /** 脉冲宽度（ms），仅展示用 */
    const val PULSE_MS = 500

    const val PREFS_NAME = "esp_prefs"
    const val KEY_DEVICE_ID = "device_id"
}
