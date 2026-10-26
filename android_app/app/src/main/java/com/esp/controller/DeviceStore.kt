package com.esp.controller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备仓库：一个 App 可保存多台 ESP 设备，多台手机保存同一份配对信息即可同时控制。
 *
 * v1.3 安全模型：每台 ESP 在配网时由固件生成随机 deviceId + secretKey（密钥）。
 * 控制命令走主题 io/<deviceId>/cmd，且 payload 必须带正确的 key 才执行。
 * 所以"把设备分享给另一部手机"就是把这个 (deviceId, key) 一并告诉对方——扫码二维码
 * （二维码里同时含 id 与 key）是推荐方式；手动输入只能填 id（适合遗留未注册密钥的设备）。
 *
 * 存储：SharedPreferences 里存一个 JSON 数组
 *   {"selected":"a1b2c3d4...",
 *    "devices":[{"id":"a1b2c3d4...","name":"客厅灯","key":"...."}, ...]}
 * 用 Android 自带的 org.json，避免引入 Gson。
 */
data class Device(val id: String, val name: String, val key: String = "")

object DeviceStore {

    private const val KEY_DEVICES = "devices_json"
    private const val KEY_SELECTED = "selected_id"

    /** 与固件 DEVID_MAX / KEY_MAX 对齐：设备ID 16 位十六进制，密钥 32 位 */
    private const val DEVICE_ID_LEN = 16
    private const val KEY_LEN = 32

    /**
     * 兼容修复（v1.4）：固件 v1.3 的 genHex 把"字符数"当成了"字节数"，
     * 生成长度是要求的 2 倍；而 EEPROM 只存 DEVID_MAX=16 / KEY_MAX=32。
     * 于是配网响应回给手机的是 32 位 ID / 64 位密钥，设备重启后实际用的是 16 位 / 32 位，
     * 两端 MQTT 主题(io/<id>/state)对不上 → 手机收不到状态、命令也到不了设备
     * （典型现象：串口打印 MQTT connected，App 却显示"无响应(可能离线)"）。
     *
     * 固件已在 v1.4 修正；这里对旧固件产生的历史数据做一次性裁剪，
     * 使已配网的设备无需重新烧录/重配即可恢复通信。长度未超限的数据原样返回，
     * 因此对新设备无任何副作用。
     */
    private fun normalized(d: Device): Device {
        val nid = if (d.id.length > DEVICE_ID_LEN) d.id.substring(0, DEVICE_ID_LEN) else d.id
        val nkey = if (d.key.length > KEY_LEN) d.key.substring(0, KEY_LEN) else d.key
        return if (nid == d.id && nkey == d.key) d else d.copy(id = nid, key = nkey)
    }

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<Device> {
        val raw = sp(ctx).getString(KEY_DEVICES, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Device>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(normalized(Device(o.getString("id"),
                    o.optString("name", o.getString("id")),
                    o.optString("key", ""))))
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, devices: List<Device>) {
        val arr = JSONArray()
        devices.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("name", it.name)
            o.put("key", it.key)
            arr.put(o)
        }
        sp(ctx).edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    /** 兼容旧版本：老版本只在 Prefs 里存了一个 deviceId，升级后自动迁移进列表 */
    fun migrateLegacy(ctx: Context) {
        val legacy = Prefs.getDeviceId(ctx)
        if (!legacy.isNullOrEmpty() && list(ctx).none { it.id == legacy }) {
            add(ctx, Device(legacy, legacy))
        }
        val selected = sp(ctx).getString(KEY_SELECTED, null)
        if (selected.isNullOrEmpty()) setSelected(ctx, legacy)
    }

    /** @return true 表示新增，false 表示已存在（不重复添加） */
    fun add(ctx: Context, device: Device): Boolean {
        val dev = normalized(device)      // 先把旧固件放大的 id/key 裁到固件实际长度
        val devices = list(ctx)
        if (devices.any { it.id == dev.id }) {
            setSelected(ctx, dev.id)
            return false
        }
        devices.add(dev)
        save(ctx, devices)
        setSelected(ctx, dev.id)
        return true
    }

    fun remove(ctx: Context, id: String) {
        val devices = list(ctx).filter { it.id != id }
        save(ctx, devices)
        if (getSelected(ctx) == id) setSelected(ctx, devices.firstOrNull()?.id)
    }

    fun rename(ctx: Context, id: String, newName: String) {
        val devices = list(ctx).map { if (it.id == id) it.copy(name = newName) else it }
        save(ctx, devices)
    }

    fun getSelected(ctx: Context): String? {
        val id = sp(ctx).getString(KEY_SELECTED, null)
        return if (!id.isNullOrEmpty() && list(ctx).any { it.id == id }) id
               else list(ctx).firstOrNull()?.id
    }

    fun setSelected(ctx: Context, id: String?) {
        if (id == null) sp(ctx).edit().remove(KEY_SELECTED).apply()
        else sp(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    fun find(ctx: Context, id: String): Device? = list(ctx).firstOrNull { it.id == id }

    fun count(ctx: Context): Int = list(ctx).size
}
