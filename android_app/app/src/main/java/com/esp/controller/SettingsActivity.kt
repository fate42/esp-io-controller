package com.esp.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/**
 * 设置页：遥控器连接状态 + 设备的增删改 + 三种添加方式。
 *
 * v1.4 新增「遥控器连接状态」，做法很轻量：
 *   临时建一条 MQTT 连接订阅 io/<id>/state。固件会以 retain 方式发布心跳（online），
 *   离线时则由遗嘱消息(LWT)发 offline，所以订阅后立刻就能收到当前状态，
 *   无需长连接、也不污染控制页的连接。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var lvDevices: ListView
    private lateinit var tvRemoteStatus: TextView
    private lateinit var tvRemoteDevice: TextView
    private lateinit var btnCheckStatus: MaterialButton

    private var devices: MutableList<Device> = mutableListOf()

    /** 状态查询用的临时 MQTT 连接 */
    private var checker: MqttManager? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val STATUS_QUERY_TIMEOUT_MS = 7_000L
    }

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
        tvRemoteStatus = findViewById(R.id.tvRemoteStatus)
        tvRemoteDevice = findViewById(R.id.tvRemoteDevice)
        btnCheckStatus = findViewById(R.id.btnCheckStatus)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnProvision).setOnClickListener {
            startActivity(Intent(this, ProvisionActivity::class.java))
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnManual).setOnClickListener { showManualAddDialog() }
        btnCheckStatus.setOnClickListener { checkRemoteStatus() }

        lvDevices.setOnItemClickListener { _, _, pos, _ ->
            showDeviceActions(devices.getOrNull(pos) ?: return@setOnItemClickListener)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkRemoteStatus()          // 每次回到本页自动刷新一次状态
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        releaseChecker()
        super.onDestroy()
    }

    private fun refresh() {
        devices = DeviceStore.list(this)
        val sel = DeviceStore.getSelected(this)
        val labels = devices.map {
            val mark = if (it.id == sel) "  · 当前" else ""
            val name = if (it.name == it.id) it.id else "${it.name}（${it.id}）"
            "$name$mark"
        }
        lvDevices.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    /* ---------------- 遥控器连接状态 ---------------- */

    private fun checkRemoteStatus() {
        val id = DeviceStore.getSelected(this)
        if (id == null) {
            tvRemoteStatus.text = getString(R.string.status_no_device)
            tvRemoteStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvRemoteDevice.text = ""
            return
        }
        val dev = DeviceStore.find(this, id)
        val key = dev?.key ?: ""

        tvRemoteDevice.text = "设备：${dev?.name ?: id}"
        tvRemoteStatus.text = getString(R.string.status_checking)
        tvRemoteStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        btnCheckStatus.isEnabled = false

        releaseChecker()
        var settled = false          // 只在主线程读写，无需同步
        val m = MqttManager()
        checker = m

        m.onState = { s ->
            if (!settled) {
                settled = true
                applyStatus(online = !s.contains("offline"))
                Thread { m.release(); if (checker === m) checker = null }.start()
            }
        }
        m.onConnectionChange = { /* 服务器连接与否由超时分支统一处理 */ }
        m.connect(id, key) {}

        uiHandler.postDelayed({
            if (!settled) {
                settled = true
                val brokerOk = m.isConnected()
                tvRemoteStatus.text =
                    getString(if (brokerOk) R.string.status_timeout else R.string.status_broker_fail)
                tvRemoteStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnCheckStatus.isEnabled = true
                Thread { m.release(); if (checker === m) checker = null }.start()
            }
        }, STATUS_QUERY_TIMEOUT_MS)
    }

    private fun applyStatus(online: Boolean) {
        tvRemoteStatus.text = getString(if (online) R.string.status_online else R.string.status_offline)
        tvRemoteStatus.setTextColor(
            ContextCompat.getColor(this, if (online) R.color.status_on else R.color.btn_stop)
        )
        btnCheckStatus.isEnabled = true
    }

    private fun releaseChecker() {
        val c = checker ?: return
        checker = null
        Thread { c.release() }.start()
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
                if (id.isEmpty()) {
                    toast("遥控器ID不能为空")
                    return@setPositiveButton
                }
                val name = etName.text.toString().trim().ifEmpty { id }
                addDevice(Device(id, name))
            }
            .show()
    }

    /** 兼容三种二维码内容：JSON、espctl:// 链接、纯设备ID（v1.3 起二维码含 key） */
    private fun parseScanned(text: String): Device? {
        val s = text.trim()
        return try {
            when {
                s.startsWith("{") -> {
                    val o = JSONObject(s)
                    val id = o.optString("id", o.optString("deviceId", ""))
                    if (id.isEmpty()) null else Device(
                        id,
                        o.optString("name", id).ifEmpty { id },
                        o.optString("key", "")
                    )
                }

                s.startsWith("espctl://") -> {
                    val id = s.substringAfter("device/").substringBefore("?").substringBefore("/")
                    val name = s.substringAfter("name=", "").substringBefore("&")
                    val key = s.substringAfter("key=", "").substringBefore("&")
                    if (id.isEmpty()) null else Device(id, name.ifEmpty { id }, key)
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
        toast(if (isNew) "已添加遥控器 ${device.id}" else "该遥控器已在列表中，已设为当前")
    }

    /* ---------------- 设备操作 ---------------- */

    private fun showDeviceActions(device: Device) {
        val items = arrayOf(
            "设为当前遥控器", getString(R.string.rename_device),
            getString(R.string.share_device), getString(R.string.delete_device)
        )
        AlertDialog.Builder(this)
            .setTitle(device.name.ifEmpty { device.id })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        DeviceStore.setSelected(this, device.id)
                        refresh()
                        toast("已切换到 ${device.name.ifEmpty { device.id }}")
                    }

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
            put("key", device.key)        // v1.3：二维码携带密钥，扫码即可配对控制
        }.toString()

        val bitmap: Bitmap? = try {
            BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 720, 720)
        } catch (e: Exception) {
            null
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val iv = ImageView(this).apply { setImageBitmap(bitmap) }
        val secureHint =
            if (device.key.isNotEmpty()) "（已配对密钥，扫码可安全控制）" else "（无密钥：仅兼容遗留设备）"
        val tv = TextView(this).apply {
            text = "${getString(R.string.qr_title)}\n${device.id}\n$secureHint"
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(iv)
        layout.addView(tv)

        AlertDialog.Builder(this)
            .setView(layout)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制分享信息") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("esp_share", payload))
                toast(getString(R.string.copied))
            }
            .show()
    }

    private fun confirmDelete(device: Device) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_device))
            .setMessage("确定删除 ${device.name.ifEmpty { device.id }} ？（只是从本手机移除，遥控器本身不受影响）")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                DeviceStore.remove(this, device.id)
                refresh()
                checkRemoteStatus()
            }
            .show()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
