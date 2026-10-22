# ESP8266 四路 IO 脉冲控制套件（手机 App + 固件）

一套**配套**的软硬件方案：手机 App 配置 ESP8266 联网，并用四个按键远程控制
ESP8266 的 4 个 GPIO（GPIO12/13/14/16），每次按下给对应 IO 一个 **500ms 高电平脉冲**。

## 架构（D 模式：公共 MQTT，零成本）

```
 ESP8266（连你家WiFi）  ⇄  公共MQTT broker(broker.emqx.io:1883)  ⇄  安卓App
        设备ID = "ESP_" + MAC后6位（与热点名一致）
```

- 配网：ESP 首启开热点 `ESP_XXXXXX`（密码 `espconfig`），App 连上后在 App 内填家中
  WiFi 账号密码并发送；ESP 保存后重启，自动连网并连上 broker。
- 控制：App 发布 `esp8266/<deviceId>/cmd` → `{"pin":12}`；ESP 把对应 GPIO 拉高
  500ms 再拉低，并回传状态到 `esp8266/<deviceId>/state`。

## 目录

- `esp8266_controller/` —— ESP8266 固件（Arduino）
- `android_app/` —— 安卓 App（Kotlin + Gradle）

## 使用流程

1. **烧录固件**：用 Arduino IDE（或 PlatformIO）把 `esp8266_controller/esp8266_controller.ino`
   烧录到 ESP8266（详见其目录内 README）。
2. **配网**：ESP 上电后，手机 WiFi 列表出现 `ESP_XXXXXX` 热点，连上（密码 `espconfig`）；
   打开 App → 自动进入「配网」页 → 点「扫描 ESP 热点」→ 选中热点 → 输入家中 WiFi
   SSID/密码 → 点「发送配置并连接」。
3. **控制**：配置成功后 App 自动切回手机 WiFi 并进入「控制」页，四个按钮即可远程触发
   对应 GPIO 的 500ms 高脉冲。

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
| 固件镜像 | `build-firmware.yml` | `merged-firmware.bin` | 烧录到 ESP8266 |

- 进仓库 **Actions** 标签页 → 对应工作流 → 运行记录底部 **Artifacts** 下载。
  （手机 GitHub App 不能直接下产物，请用**手机浏览器桌面模式**或电脑下载。）
- 安卓工程也可在本机 Android Studio 打开 `android_app/` → Build → Build APK(s)。
- 固件也可在本地用 PlatformIO（`pio run -t upload`）或 Arduino IDE 烧录，详见 `esp8266_controller/README.md`。

> 若公共 MQTT 端口 1883 被某些网络封锁，可把固件与 App 的 broker 改为支持 WebSocket 的
> `broker.emqx.io:8083`（代码已预留 `MQTT_PORT`）。

> 说明：公共 MQTT broker 是开放的，消息理论上对同 topic 可见；本方案用随机 clientId +
> 设备ID 隔离，适合个人/实验用途。如需更强隐私，可自建 MQTT broker 或改用 TLS 端口 8883。
