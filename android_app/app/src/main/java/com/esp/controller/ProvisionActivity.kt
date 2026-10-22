package com.esp.controller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 配网页：扫描并连接 ESP 热点，然后把家中 WiFi 账号密码发给 ESP。
 */
class ProvisionActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var lvAps: ListView
    private lateinit var etHomeSsid: EditText
    private lateinit var etHomePass: EditText
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView

    private val apList = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var selectedAp: String? = null
    private var originalNetId = -1

    private val handler = Handler(Looper.getMainLooper())

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (updated) showAps()
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) scanAp() else toast("需要定位权限才能扫描 WiFi 热点")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provision)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lvAps = findViewById(R.id.lvAps)
        etHomeSsid = findViewById(R.id.etHomeSsid)
        etHomePass = findViewById(R.id.etHomePass)
        btnSend = findViewById(R.id.btnSend)
        tvStatus = findViewById(R.id.tvStatus)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, apList)
        lvAps.adapter = adapter
        lvAps.choiceMode = ListView.CHOICE_MODE_SINGLE
        lvAps.setOnItemClickListener { _, _, pos, _ ->
            val item = apList[pos]
            if (item.startsWith(Constants.AP_PREFIX)) {
                selectedAp = item
                tvStatus.append("\n已选择热点: $item ，正在连接…")
                connectToAp(item)
            }
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { checkAndScan() }
        btnSend.setOnClickListener { sendConfig() }
        findViewById<Button>(R.id.btnSkip).setOnClickListener {
            val id = Prefs.getDeviceId(this)
            if (!id.isNullOrEmpty()) gotoControl() else toast("尚未配网，请先扫描并发送配置")
        }

        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
            toast("正在打开 WiFi…")
        }
        originalNetId = wifiManager.connectionInfo?.networkId ?: -1
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(wifiScanReceiver) } catch (_: Exception) {}
    }

    private fun checkAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val need = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val missing = need.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (missing.isEmpty()) scanAp() else permLauncher.launch(missing)
        } else {
            scanAp()
        }
    }

    private fun scanAp() {
        if (!wifiManager.isWifiEnabled) wifiManager.isWifiEnabled = true
        tvStatus.text = "扫描中…"
        wifiManager.startScan()
        showAps()
    }

    private fun showAps() {
        apList.clear()
        val results = wifiManager.scanResults ?: return
        for (r in results) {
            if (r.SSID.startsWith(Constants.AP_PREFIX)) apList.add(r.SSID)
        }
        if (apList.isEmpty()) apList.add("（未找到 ESP 热点，请确认 ESP 已上电；或手动点此重试）")
        adapter.notifyDataSetChanged()
        if (apList.size == 1 && apList[0].startsWith("（")) {
            tvStatus.text = "未发现 ESP 热点，请确认 ESP 已上电后点「扫描」"
        } else {
            tvStatus.text = "发现 ${apList.size} 个 ESP 热点，点击列表项进行连接"
        }
    }

    private fun findConfigured(ssid: String): Int {
        wifiManager.configuredNetworks?.forEach {
            if (it.SSID == "\"$ssid\"") return it.networkId
        }
        return -1
    }

    private fun connectToAp(ssid: String) {
        var netId = findConfigured(ssid)
        if (netId == -1) {
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"${Constants.AP_PASSWORD}\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            netId = wifiManager.addNetwork(conf)
        }
        if (netId == -1) { toast("无法添加网络，请手动连接 $ssid"); return }
        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()
        handler.postDelayed({ checkApConnected(ssid) }, 4000)
    }

    private fun checkApConnected(ssid: String) {
        val info = wifiManager.connectionInfo
        if (info != null && info.ssid == "\"$ssid\"") {
            tvStatus.append("\n已连上 $ssid，请在下方输入家中 WiFi 并发送")
        } else {
            tvStatus.append("\n尚未连上热点（当前>${info?.ssid}），请确认密码或手动连接后重试")
        }
    }

    private fun sendConfig() {
        val homeSsid = etHomeSsid.text.toString().trim()
        val homePass = etHomePass.text.toString()
        if (homeSsid.isEmpty()) { toast("请输入家中 WiFi 名称"); return }
        if (selectedAp == null) { toast("请先在上方选择/连接 ESP 热点"); return }

        tvStatus.append("\n正在发送配置到 ${selectedAp}…")
        Thread {
            val deviceId = EspConfigClient.sendConfig(homeSsid, homePass)
            runOnUiThread {
                if (deviceId != null) {
                    Prefs.setDeviceId(this, deviceId)
                    tvStatus.append("\n配置成功，设备ID=$deviceId，正在切回 WiFi…")
                    if (originalNetId != -1) {
                        wifiManager.enableNetwork(originalNetId, true)
                        wifiManager.reconnect()
                    }
                    gotoControl()
                } else {
                    tvStatus.append("\n配置失败，请确认手机已连上 ESP 热点后重试")
                }
            }
        }.start()
    }

    private fun gotoControl() {
        startActivity(Intent(this, ControlActivity::class.java))
        finish()
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }
}
