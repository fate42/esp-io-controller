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

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<Device> {
        val raw = sp(ctx).getString(KEY_DEVICES, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Device>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Device(o.getString("id"),
                    o.optString("name", o.getString("id")),
                    o.optString("key", "")))
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
        val devices = list(ctx)
        if (devices.any { it.id == device.id }) {
            setSelected(ctx, device.id)
            return false
        }
        devices.add(device)
        save(ctx, devices)
        setSelected(ctx, device.id)
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
