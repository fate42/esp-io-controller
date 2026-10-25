package com.esp.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/**
 * 设置页：设备的增删改 + 三种添加方式。
 *
 * 添加设备（= 让这部手机能控制某台 ESP）有三种途径：
 *   1) 配网：给一台全新的 ESP 下发家中 WiFi（走 ProvisionActivity 热点流程）
 *   2) 扫码：扫另一台手机设置页里的设备二维码（多手机共享同一台 ESP 的推荐方式）
 *   3) 手动：直接输入设备ID（例如从聊天记录里复制来的 ESP_A1B2C3）
 *
 * 设备列表项点击可：设为当前 / 重命名 / 生成二维码 / 删除。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var lvDevices: ListView
    private var devices: MutableList<Device> = mutableListOf()

    /** 扫码：zxing-android-embedded 提供的 Activity Result 契约（自带相机权限处理） */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrEmpty()) return@registerForActivityResult
        val device = parseScanned(text)
        if (device == null) {
            toast("无法识别的二维码：$text")
        } else {
            addDevice(device)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        lvDevices = findViewById(R.id.lvDevices)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnProvision).setOnClickListener {
            startActivity(Intent(this, ProvisionActivity::class.java))
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnManual).setOnClickListener { showManualAddDialog() }

        lvDevices.setOnItemClickListener { _, _, pos, _ ->
            showDeviceActions(devices.getOrNull(pos) ?: return@setOnItemClickListener)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        devices = DeviceStore.list(this)
        val labels = devices.map {
            val mark = if (it.id == DeviceStore.getSelected(this)) " · 当前" else ""
            if (it.name == it.id) "${it.id}$mark" else "${it.name}（${it.id}）$mark"
        }
        lvDevices.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    /* ---------------- 添加设备 ---------------- */

    private fun startScan() {
        val options = ScanOptions().apply {
            setPrompt(getString(R.string.scan_tip))
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        try {
            scanLauncher.launch(options)
        } catch (e: Exception) {
            toast("无法打开相机：${e.message}")
        }
    }

    private fun showManualAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etId = EditText(this).apply {
            hint = getString(R.string.device_id_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val etName = EditText(this).apply { hint = getString(R.string.device_name_hint) }
        layout.addView(etId)
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_by_input))
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val id = etId.text.toString().trim()
                if (id.isEmpty()) { toast("设备ID不能为空"); return@setPositiveButton }
                val name = etName.text.toString().trim().ifEmpty { id }
                addDevice(Device(id, name))
            }
            .show()
    }

    /** 兼容三种二维码内容：JSON、espctl:// 链接、纯设备ID */
    private fun parseScanned(text: String): Device? {
        val s = text.trim()
        return try {
            when {
                s.startsWith("{") -> {
                    val o = JSONObject(s)
                    val id = o.optString("id", o.optString("deviceId", ""))
                    if (id.isEmpty()) null else Device(id, o.optString("name", id).ifEmpty { id })
                }
                s.startsWith("espctl://") -> {
                    val id = s.substringAfter("device/").substringBefore("?").substringBefore("/")
                    val name = s.substringAfter("name=", "").substringBefore("&")
                    if (id.isEmpty()) null else Device(id, name.ifEmpty { id })
                }
                else -> if (s.isBlank()) null else Device(s, s)
            }
        } catch (e: Exception) {
            if (s.isBlank()) null else Device(s, s)
        }
    }

    private fun addDevice(device: Device) {
        val isNew = DeviceStore.add(this, device)
        refresh()
        toast(if (isNew) "已添加设备 ${device.id}" else "设备 ${device.id} 已在列表中，已设为当前")
    }

    /* ---------------- 设备操作 ---------------- */

    private fun showDeviceActions(device: Device) {
        val items = arrayOf("设为当前设备", getString(R.string.rename_device),
            getString(R.string.share_device), getString(R.string.delete_device))
        AlertDialog.Builder(this)
            .setTitle(device.id)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { DeviceStore.setSelected(this, device.id); refresh(); toast("已切换到 ${device.id}") }
                    1 -> showRenameDialog(device)
                    2 -> showQrDialog(device)
                    3 -> confirmDelete(device)
                }
            }
            .show()
    }

    private fun showRenameDialog(device: Device) {
        val et = EditText(this).apply { setText(device.name) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_device))
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { device.id }
                DeviceStore.rename(this, device.id, name)
                refresh()
            }
            .show()
    }

    private fun showQrDialog(device: Device) {
        val payload = JSONObject().apply {
            put("id", device.id)
            put("name", device.name)
        }.toString()

        val bitmap: Bitmap? = try {
            BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 720, 720)
        } catch (e: Exception) { null }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val iv = ImageView(this).apply { setImageBitmap(bitmap) }
        val tv = TextView(this).apply {
            text = "${getString(R.string.qr_title)}\n${device.id}"
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(iv)
        layout.addView(tv)

        AlertDialog.Builder(this)
            .setView(layout)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制设备ID") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("deviceId", device.id))
                toast(getString(R.string.copied))
            }
            .show()
    }

    private fun confirmDelete(device: Device) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_device))
            .setMessage("确定删除 ${device.id} ？（只是从本手机移除，设备本身不受影响）")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                DeviceStore.remove(this, device.id)
                refresh()
            }
            .show()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
