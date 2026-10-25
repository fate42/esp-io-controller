# ESP8266 四路 IO 脉冲控制套件（手机 App + 固件）

一套**配套**的软硬件方案：手机 App 配置 ESP8266 联网，并用四个按键远程控制
ESP8266 的 4 个 GPIO（GPIO12/13/14/16），每次按下给对应 IO 一个 **500ms 高电平脉冲**。

## 架构（D 模式：公共 MQTT，零成本）

```
 ESP8266（连你家WiFi）  ⇄  公共MQTT broker(broker.emqx.io:1883)  ⇄  安卓App
        配对身份 = 随机 deviceId(16hex) + secretKey(32hex)，配网时生成
```

- 配网：ESP 首启开热点 `ESP_XXXXXX`（密码 `espconfig`，仅用于配网发现），App 连上后在 App 内填家中
  WiFi 账号密码并发送；ESP 保存后重启，自动连网并连上 broker，同时把随机 `deviceId` + `secretKey`
  回传给 App（**密钥只在本地配网链路下发，绝不进公网**）。
- 控制：App 发布 `io/<deviceId>/cmd` → `{"pin":12,"key":"..."}`；ESP 校验密钥通过后把对应 GPIO 拉高
  500ms 再拉低，并回传状态到 `io/<deviceId>/state`。`deviceId` 随机不可猜测，未持密钥者即便连上 broker
  也无法控制。

> **v1.3 安全加固**：设备身份随机化 + 命令密钥校验 + MQTT 主题随机前缀，详见
> `esp8266_controller/README.md` 的「v1.3 安全加固」一节。
> 已烧旧固件且只配过网的设备，需**恢复出厂重新配网一次**才会注册密钥（详见该节迁移提示）。

## 目录

- `esp8266_controller/` —— ESP8266 固件（Arduino）
- `android_app/` —— 安卓 App（Kotlin + Gradle）

## 使用流程

1. **烧录固件**：用 Arduino IDE（或 PlatformIO）把 `esp8266_controller/esp8266_controller.ino`
   烧录到 ESP8266（详见其目录内 README）。CI 产出的 `esp8266-io-controller-0x00000.bin`
   烧录地址为 **0x0**（不是 ESP32 的 0x10000）。
2. **添加设备**：打开 App，默认就是**控制按键界面**；点右上角「**设置**」进入设置页，
   三种方式添加设备：
   - **给新 ESP 配网**（第一次用）：ESP 上电后手机 WiFi 列表出现 `ESP_XXXXXX` 热点
     （密码 `espconfig`）→ 设置页点「给新 ESP 配网（开热点）」→ 点「扫描 ESP 热点」→
     选中热点（**首次连接系统可能弹"允许加入网络"确认框，点允许**）→ 输入家中 WiFi
     SSID/密码 → 点「发送配置并连接」→ 自动返回，设备出现在列表里。
     - Android 10 以上不再用已废弃的 `addNetwork`，改为 `WifiNetworkSpecifier + requestNetwork`
       且移除 `NET_CAPABILITY_INTERNET`（否则系统认为热点"没有互联网"而拒绝回调）；
     - 发配置前会 `bindProcessToNetwork` 绑定热点网络，否则 `192.168.4.1` 的请求走蜂窝数据超时；
     - Android 14 对 `startScan()` 限流：扫不到就先在系统 WiFi 设置里手动连 `ESP_XXXXXX`，
       再回 App 点「我已手动连上 ESP 热点（绑定该网络）」。
   - **扫码添加**（第二台及以后的手机用这个）：已在用的手机进设置页 → 点设备 →
     「生成二维码」→ 另一台手机装好 App → 设置页「扫码添加设备」扫一下即可。
   - **手动输入设备ID**：设置页「手动输入设备ID」，填 `ESP_A1B2C3` 这类 ID（可加备注名）。
3. **控制**：回到控制页，顶部下拉框选择设备（可保存多台），四个按钮即可远程触发
   对应 GPIO 的 500ms 高脉冲；页面显示设备在线/离线（固件 MQTT 遗嘱消息或心跳超时判定）。

**多台手机同时控制同一台 ESP**：每台 ESP 在配网时生成随机 `deviceId` + `secretKey`，
命令走随机主题 `io/<deviceId>/cmd` 且必须带正确密钥才执行。所以"把设备分享给另一部手机"就是把
这份 (id, key) 交给对方——设置页「生成二维码」会把二者一起编码，对方「扫码添加」即可配对控制
（推荐扫码，不要只发 deviceId）。多台手机可同时在线、同时按键，固件收到一条就触发一次。

**恢复出厂（忘记/改了路由器密码时）**：两种方式任选
- 访问 `http://<ESP的IP>/reset`（联网状态）或 `http://192.168.4.1/reset`（热点状态）；
- 或者：按住开发板上的 FLASH（GPIO0）键再上电，持续 2 秒。

## 自定义

- 脉冲宽度：固件 `#define PULSE_MS 500`
- 引脚：固件 `GPIOS[]` 数组（按钮顺序 1~4）
- MQTT broker：固件 `MQTT_SERVER` / `MQTT_PORT`
- 热点密码：固件 `AP_PASSWORD`（App 内 `Constants.AP_PASSWORD` 需保持一致）

## 编译 / 获取成品（无需本地编译环境）

两个工程都配好了 **GitHub Actions**，推送或手动触发即自动出包，在网页 Artifacts 区下载即可：

| 产物 | 工作流 | 下载内容 | 用途 |
|------|--------|----------|------|
| 安卓 App | `build-apk.yml` | `app-debug.apk` | 手机安装控制端 |
| 固件镜像 | `build-firmware.yml` | `esp8266-io-controller-0x00000.bin` | 烧录到 ESP8266（地址 0x0） |

- 进仓库 **Actions** 标签页 → 对应工作流 → 运行记录底部 **Artifacts** 下载。
  （手机 GitHub App 不能直接下产物，请用**手机浏览器桌面模式**或电脑下载。）
- 安卓工程也可在本机 Android Studio 打开 `android_app/` → Build → Build APK(s)。
- 固件也可在本地用 PlatformIO（`pio run -t upload`）或 Arduino IDE 烧录，详见 `esp8266_controller/README.md`。

> 若公共 MQTT 端口 1883 被某些网络封锁，可把固件与 App 的 broker 改为支持 WebSocket 的
> `broker.emqx.io:8083`（代码已预留 `MQTT_PORT`）。

> 说明：公共 MQTT broker 是开放的，消息理论上对同 topic 可见；本方案 v1.3 起用随机 deviceId +
> 随机主题前缀 + 命令密钥三重隔离，未持密钥者无法控制，适合个人/实验用途。如需更强隐私，
> 可自建带账号密码鉴权的 MQTT broker 或改用 TLS 端口 8883。
