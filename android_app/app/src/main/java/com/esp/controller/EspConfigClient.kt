package com.esp.controller

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 通过 ESP 的热点网关(192.168.4.1)发送 WiFi 配置。
 * 成功返回设备ID，失败返回 null。
 */
object EspConfigClient {

    fun sendConfig(ssid: String, password: String): String? {
        return try {
            val url = URL(Constants.CONFIG_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "ssid=" + URLEncoder.encode(ssid, "UTF-8") +
                       "&password=" + URLEncoder.encode(password, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val text = try {
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()

            if (code == 200) {
                // 从 {"status":"ok","deviceId":"ESP_XXXXXX"} 中提取 deviceId
                val i = text.indexOf("\"deviceId\"")
                if (i >= 0) {
                    val colon = text.indexOf(':', i)
                    val s = text.indexOf('"', colon + 1)
                    val e = text.indexOf('"', s + 1)
                    if (s >= 0 && e > s) return text.substring(s + 1, e)
                }
                "ESP_UNKNOWN"
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
