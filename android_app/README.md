# 安卓 App：车库门钥匙

Kotlin + Gradle 原生安卓工程，配合 `esp8266_controller` 固件使用。

> **关于 APK**：本工程代码已完整实现。由于当前生成环境网络对大文件限速，无法在此直接编译出
> APK，请用 **Android Studio 打开本目录 → Build → Build APK(s)** 一键生成（约 1 分钟，详见下方
> 「构建方式」）。生成的 `app-debug.apk` 可直接安装到手机。

## 功能

- **控制页（ControlActivity，App 打开后的默认界面）**：四个大按钮为 **开门 / 关门 / 锁门 / 停止**，
  分别对应 GPIO12 / GPIO13 / GPIO14 / GPIO16；按下即向 `io/<deviceId>/cmd` 发布
  `{"pin":X,"key":"..."}`（v1.3 起带配对密钥），触发 ESP 输出 500ms 高脉冲。
  左下角用圆点+文字显示遥控器**已连接/断开**（= MQTT 连通且心跳新鲜），不再暴露 MQTT 等技术文案；
  操作反馈为「开门按键已按下」。顶部下拉框在多台遥控器间切换（仅多于 1 台时出现），
  右上角齿轮进设置页；没有设备时显示空状态引导添加。
- **设置页（SettingsActivity）**：三种方式添加设备 ——
  ① 给新 ESP 配网（开热点，走 `ProvisionActivity`）；
  ② **扫码添加**（扫另一台手机生成的设备二维码，实现多手机共用一台 ESP）；
  ③ 手动输入设备ID。还可以重命名、删除、生成分享二维码。
- **配网页（ProvisionActivity）**：扫描并连接 ESP 热点 `ESP_XXXXXX`，把家中 WiFi 账号密码
  通过 `http://192.168.4.1/config` 发给 ESP；成功后写入设备列表并返回设置页。

> 多台手机控制同一台 ESP 的原理（v1.3）：每台 ESP 在配网时由固件生成随机 `deviceId` + `secretKey`，
> 命令走随机主题 `io/<deviceId>/cmd` 且必须带正确密钥才执行；"分享设备"就是把这份 (id, key)
> 交给另一部手机——设置页的「生成二维码」会把二者一起编码，对方「扫码添加」即可配对控制。

## v1.4 变更（车库门钥匙）

- App 名称改为「车库门钥匙」，并新增启动图标（`mipmap-*` 各密度 + `mipmap-anydpi-v26` 自适应图标）。
- 四键语义化为 开门/关门/锁门/停止，界面不再显示 GPIO 编号。
- 主界面移除「MQTT: xxx」技术文案，改为左下角状态圆点 + 已连接/断开。
- 操作提示改为「XX 按键已按下」。
- 设置页新增「遥控器连接状态」卡片：临时订阅 `io/<id>/state`，借固件的 retain 心跳与
  LWT 遗嘱消息即时判断在线/离线，并支持手动刷新。
- 整体改为浅色卡片风格（统一配色、大圆角按键、图标化入口），更接近消费级 App。

### 按键 ↔ 硬件输出对应（接线按此对应）

| 界面按键 | 固件 GPIO | 输出 |
|---|---|---|
| 开门 | GPIO12 | 500ms 高脉冲 |
| 关门 | GPIO13 | 500ms 高脉冲 |
| 锁门 | GPIO14 | 500ms 高脉冲 |
| 停止 | GPIO16 | 500ms 高脉冲 |

## 权限说明

- `INTERNET`：连接 MQTT broker
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`：连接 ESP 配网热点
- `ACCESS_FINE_LOCATION`（≤ Android 12）/ `NEARBY_WIFI_DEVICES`（Android 13+）：
  扫描 WiFi 列表需要，声明为 `neverForLocation`，不采集位置
- `CHANGE_NETWORK_STATE` / `WAKE_LOCK`：绑定配网网络、保持 MQTT 长连接

## v1.1 配网链路说明（Android 10+ 必须这样写）

1. 连接热点用 `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork()`，
   并且 NetworkRequest **必须 `removeCapability(NET_CAPABILITY_INTERNET)`** ——
   ESP 热点没有互联网出口，保留该能力系统会一直不给 `onAvailable` 回调。
   旧的 `WifiManager.addNetwork/enableNetwork` 在 Android 10+ 已失效（返回 -1）。
2. 拿到 `Network` 后调用 `bindProcessToNetwork(network)`，并用
   `network.openConnection(url)` 发 HTTP —— 否则 `192.168.4.1` 的请求会走蜂窝数据而超时。
3. Android 14 对 `startScan()` 有频率限制：扫不到时，可在系统 WiFi 设置里手动连
   `ESP_XXXXXX`，回 App 点「我已手动连上 ESP 热点（绑定该网络）」。

## 构建方式

### 方式 A：Android Studio（推荐，最简单）
1. 用 Android Studio 打开本目录 `android_app/`
2. 等待 Gradle 同步完成（会联网下载依赖）
3. 菜单 Build → Build Bundle(s) / APK(s) → Build APK(s)
4. 生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`
5. 拷贝到手机安装（需允许「未知来源」安装）

### 方式 B：命令行（Gradle，无需 Android Studio）
需要：JDK 17 + Android SDK（platform-tools、platforms;android-34、build-tools;34.0.0）。

> 本工程**未附带 gradle-wrapper.jar**（二进制无法在此生成），因此请用你本机已安装的 Gradle；
> 或先用 `gradle wrapper` 生成包装器再 `./gradlew assembleDebug`。

```bash
# 设置环境变量
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH

# 在项目根目录执行（用本机已安装的 gradle）
gradle assembleDebug

# 产出 APK
# app/build/outputs/apk/debug/app-debug.apk
```

> 若你的环境网络良好且想做全自动化（装 JDK+SDK+Gradle 并构建），可参考仓库同级目录的
> `D:/android_build/setup.sh` 脚本思路（它演示了无 Android Studio 的从头构建流程）。

### 方式 C：GitHub Actions 云端编译（无需本机任何环境）★ 没有编译环境时首选
仓库已内置工作流 `.github/workflows/build-apk.yml`，在 GitHub 的 runner 上自动装好
JDK17 + Android SDK 并出包，你只需下载产物：

1. 把本仓库（含 `android_app/` 与 `.github/`）推送到一个 GitHub 仓库
2. 进入仓库 **Actions → Build Android APK → Run workflow**（或直接 push 到 main/master 触发）
3. 构建完成后在 **Artifacts** 里下载 `app-debug-apk`（即 `app-debug.apk`）
4. 下载到手机安装即可

> 注意：本工程未附带 `gradle-wrapper.jar`，工作流用 `gradle/gradle-build-action` 指定
> `gradle-version: 8.5` 自动下载 Gradle，无需 wrapper。
> 若你的 GitHub 仓库只放 `android_app/` 子目录，请把 `.github/` 也放到仓库根。

> 脚本 `D:/android_build/setup.sh` 演示了如何从无到有安装 JDK17 + SDK + Gradle（装到 D 盘）。
> 若本机已装 Android Studio，直接用方式 A 即可，无需重复安装。

## 部署到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 故障排查

- **配网页扫描不到 ESP 热点**：确认 ESP 已上电；Android 10+ 需授予 WiFi/定位权限且打开系统定位；
  偶尔需点「扫描」多试几次。也可手动在系统 WiFi 设置里连上 `ESP_XXXXXX` 后，
  回 App 点「我已手动连上 ESP 热点（绑定该网络）」。
- **点了热点但提示"连接失败/系统未给出可用网络"**：Android 10+ 首次连接会弹系统确认框，
  必须点允许；同时确认热点密码与固件 `AP_PASSWORD` 一致。
- **连上热点但"配置失败"**：说明 HTTP 没走 WiFi，检查是否已成功绑定网络；
  可用手机浏览器（先关掉移动数据）访问 `http://192.168.4.1/status` 验证链路。
- **控制页一直「MQTT: 断开」**：检查手机是否连着有外网的 WiFi；部分网络会封锁 1883 端口，
  可把固件与 App 的 broker 改成支持 WebSocket 的 `broker.emqx.io:8083`（代码已预留 `MQTT_PORT`）。
- **按钮按下无反应**：看 ESP 串口日志是否收到 `{"pin":...}` 与 `[IO] pin X -> HIGH`；
  并确认 GPIO 接线与固件 `GPIOS[]` 一致。
