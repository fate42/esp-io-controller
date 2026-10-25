package com.esp.controller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 配网页：发现 ESP 热点 → 连接并把进程绑定到该网络 → 把家中 WiFi 账号密码发给 ESP。
 *
 * v1.1 关键修复（原实现在 Android 10+ 基本不可用）：
 *   1) Android 10(API 29)+ 不再使用已废弃的 WifiManager.addNetwork/enableNetwork
 *      （系统会返回 -1 或直接拒绝），改为 WifiNetworkSpecifier + ConnectivityManager
 *      .requestNetwork()，并在 NetworkRequest 中移除 NET_CAPABILITY_INTERNET，
 *      否则系统判定"热点无互联网"而永远不回调 onAvailable。
 *   2) 拿到 Network 后调用 bindProcessToNetwork()，让 HttpURLConnection 走 WiFi 而不是蜂窝数据，
 *      否则 http://192.168.4.1 的请求会被默认网络（移动数据）劫持而超时。
 *   3) Android 13+ 申请 NEARBY_WIFI_DEVICES；Android 14 对 startScan 有节流，
 *      额外提供"手动连接后绑定当前网络"的兜底入口。
 */
class ProvisionActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var lvAps: ListView
    private lateinit var etHomeSsid: EditText
    private lateinit var etHomePass: EditText
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView

    private val apList = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var selectedAp: String? = null

    /** 已绑定（进程流量强制走它）的 ESP 热点网络 */
    private var boundNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastRequestedSsid: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) showAps()
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) scanAp() else toast("需要 WiFi/定位相关权限才能扫描热点")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provision)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
                tvStatus.append("\n已选择热点: $item，正在请求连接…")
                connectToAp(item)
            }
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { checkAndScan() }
        btnSend.setOnClickListener { sendConfig() }
        findViewById<Button>(R.id.btnBindCurrent).setOnClickListener {
            tvStatus.append("\n正在绑定当前 WiFi 网络…")
            bindCurrentWifiNetwork()
        }
        findViewById<Button>(R.id.btnSkip).setOnClickListener {
            val id = DeviceStore.getSelected(this)
            if (!id.isNullOrEmpty()) finishProvision() else toast("尚未配网，请先扫描并发送配置")
        }

        ensureWifiOn()
    }

    /** Android 10+ 起第三方应用无法直接打开 WiFi，失败时提示用户手动开启 */
    private fun ensureWifiOn() {
        if (wifiManager.isWifiEnabled) return
        try {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = true
            toast("正在打开 WiFi…")
        } catch (e: Exception) {
            tvStatus.append("\n无法自动打开 WiFi，请在系统设置中开启后返回")
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(wifiScanReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        releaseNetwork()
        super.onDestroy()
    }

    /* ---------------- 扫描 ---------------- */

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun checkAndScan() {
        val need = requiredPermissions()
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) scanAp() else permLauncher.launch(missing)
    }

    private fun scanAp() {
        ensureWifiOn()
        tvStatus.text = "扫描中…"
        @Suppress("MissingPermission")
        val started = wifiManager.startScan()
        if (!started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tvStatus.append("\n系统拒绝了本次扫描（Android 14+ 对扫描有频率限制），" +
                "可改用下方「我已手动连上 ESP 热点」")
        }
        handler.postDelayed({ showAps() }, 1500)
    }

    private fun showAps() {
        apList.clear()
        val results = try { wifiManager.scanResults } catch (e: SecurityException) { null } ?: return
        for (r in results) {
            if (r.SSID.startsWith(Constants.AP_PREFIX) && !apList.contains(r.SSID)) apList.add(r.SSID)
        }
        if (apList.isEmpty()) {
            apList.add("（未找到 ESP 热点：请确认已上电；Android 14+ 若被限流请手动连接）")
        }
        adapter.notifyDataSetChanged()
        if (apList.size == 1 && apList[0].startsWith("（")) {
            tvStatus.text = "未发现 ESP 热点，请确认 ESP 已上电后点「扫描」"
        } else {
            tvStatus.text = "发现 ${apList.size} 个 ESP 热点，点击列表项进行连接"
        }
    }

    /* ---------------- 连接热点 ---------------- */

    private fun connectToAp(ssid: String) {
        releaseNetwork()
        lastRequestedSsid = ssid
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestSpecifierNetwork(ssid)
        } else {
            connectLegacy(ssid)
        }
    }

    /** Android 10+：用 WifiNetworkSpecifier 请求一个"不要求互联网"的 WiFi 网络 */
    private fun requestSpecifierNetwork(ssid: String) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(Constants.AP_PASSWORD)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // 关键：ESP 热点没有互联网出口
            .setNetworkSpecifier(specifier)
            .build()
        registerNetworkRequest(request, "正在请求系统连接到 $ssid（首次可能弹出确认框）…")
    }

    /** 兜底：绑定当前已连上的 WiFi（用户手动在系统设置里连了热点时用） */
    private fun bindCurrentWifiNetwork() {
        releaseNetwork()      // 同一个 NetworkCallback 实例不能重复注册，先释放旧的
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        registerNetworkRequest(request, "正在绑定当前 WiFi 网络…")
    }

    @Suppress("MissingPermission")
    private fun registerNetworkRequest(request: NetworkRequest, tip: String) {
        tvStatus.append("\n$tip")
        val cb = wifiNetworkCallback
        networkCallback = cb
        try {
            connectivityManager.requestNetwork(request, cb, 30_000L)
        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.append("\n发起网络请求失败：${e.message}")
        }
    }

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connectivityManager.bindProcessToNetwork(network)
                } else {
                    ConnectivityManager.setProcessDefaultNetwork(network)
                }
                if (ok) {
                    boundNetwork = network
                    if (selectedAp == null) selectedAp = lastRequestedSsid
                    tvStatus.append("\n已连接并绑定网络 ✓ 现在可以填写家中 WiFi 并发送")
                } else {
                    tvStatus.append("\n绑定网络失败，请重试")
                }
            }
        }

        override fun onUnavailable() {
            runOnUiThread {
                tvStatus.append("\n连接失败：系统未给出可用网络（密码错误/热点未上电/被系统拒绝）")
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                boundNetwork = null
                tvStatus.append("\n热点连接已断开，请重新连接")
            }
        }
    }

    /** Android 9 及以下：仍可用旧的 addNetwork 路径 */
    @Suppress("DEPRECATION")
    private fun connectLegacy(ssid: String) {
        var netId = -1
        wifiManager.configuredNetworks?.forEach {
            if (it.SSID == "\"$ssid\"") netId = it.networkId
        }
        if (netId == -1) {
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"${Constants.AP_PASSWORD}\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            netId = try { wifiManager.addNetwork(conf) } catch (e: Exception) { -1 }
        }
        if (netId == -1) {
            tvStatus.append("\n无法添加网络，请在系统 WiFi 设置中手动连接 $ssid 后点「绑定该网络」")
            return
        }
        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()
        handler.postDelayed({ bindCurrentWifiNetwork() }, 4000)
    }

    private fun releaseNetwork() {
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { connectivityManager.bindProcessToNetwork(null) } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            ConnectivityManager.setProcessDefaultNetwork(null)
        }
        boundNetwork = null
    }

    /* ---------------- 下发配置 ---------------- */

    private fun sendConfig() {
        val homeSsid = etHomeSsid.text.toString().trim()
        val homePass = etHomePass.text.toString()
        if (homeSsid.isEmpty()) { toast("请输入家中 WiFi 名称"); return }
        val network = boundNetwork
        if (network == null) { toast("请先连接并绑定 ESP 热点（列表点选或点「绑定该网络」）"); return }

        tvStatus.append("\n正在发送配置到 ${selectedAp ?: "ESP 热点"}…")
        btnSend.isEnabled = false
        Thread {
            val deviceId = EspConfigClient.sendConfig(network, homeSsid, homePass)
            runOnUiThread {
                btnSend.isEnabled = true
                if (deviceId != null) {
                    // 加入设备列表并设为当前（Prefs 同时写一份，兼容老版本读取）
                    DeviceStore.add(this, Device(deviceId, deviceId))
                    Prefs.setDeviceId(this, deviceId)
                    tvStatus.append("\n配置成功，设备ID=$deviceId")
                    releaseNetwork()          // 解绑，让流量回到正常网络
                    toast("已添加设备 $deviceId")
                    handler.postDelayed({ finishProvision() }, 800)
                } else {
                    tvStatus.append("\n配置失败：请确认手机仍连着 ESP 热点（可用浏览器访问 192.168.4.1/status 验证）")
                }
            }
        }.start()
    }

    /** 配网结束返回上一页（设置页），列表会在 onResume 里自动刷新出新设备 */
    private fun finishProvision() {
        finish()
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }
}
