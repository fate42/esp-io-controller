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
 * v1.3：/config 响应会同时返回 deviceId 与 secretKey（配对密钥），
 * 返回的 [EspIdentity] 同时携带二者供上层存入设备列表。
 *
 * @param network 由 ProvisionActivity 通过 bindProcessToNetwork 持有的热点网络；为 null 时退回默认网络
 * @return 成功返回设备身份(deviceId+key)，失败返回 null
 */
object EspConfigClient {

    data class EspIdentity(val id: String, val key: String)

    fun sendConfig(network: Network?, ssid: String, password: String): EspIdentity? {
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
                // 从 {"status":"ok","deviceId":"...","key":"..."} 提取 deviceId 与 key
                val id = extractField(text, "deviceId") ?: "ESP_UNKNOWN"
                val key = extractField(text, "key") ?: ""
                EspIdentity(id, key)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 从 JSON 文本里提取某个带双引号的字符串字段 */
    private fun extractField(text: String, field: String): String? {
        val i = text.indexOf("\"$field\"")
        if (i < 0) return null
        val colon = text.indexOf(':', i)
        if (colon < 0) return null
        val s = text.indexOf('"', colon + 1)
        if (s < 0) return null
        val e = text.indexOf('"', s + 1)
        if (e <= s) return null
        return text.substring(s + 1, e)
    }
}
