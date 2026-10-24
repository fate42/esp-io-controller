package com.esp.controller

import android.net.Network
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 通过 ESP 的热点网关(192.168.4.1)发送 WiFi 配置。
 *
 * v1.1 关键修复：必须把请求绑到"ESP 热点"这个 Network 上。
 * ESP 热点没有互联网出口，Android 会把默认网络切到蜂窝数据，
 * 直接用 URL.openConnection() 发往 192.168.4.1 会走移动数据而超时。
 * 这里统一用 network.openConnection(url)，保证流量走 WiFi。
 *
 * @param network 由 ProvisionActivity 通过 bindProcessToNetwork 持有的热点网络；为 null 时退回默认网络
 * @return 成功返回设备ID，失败返回 null
 */
object EspConfigClient {

    fun sendConfig(network: Network?, ssid: String, password: String): String? {
        return try {
            val url = URL(Constants.CONFIG_URL)
            val conn = (network?.openConnection(url)
                ?: url.openConnection()) as HttpURLConnection
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
