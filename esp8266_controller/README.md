# ESP8266 四路 IO 脉冲控制器（固件）

## 功能
- 首次上电开热点 `ESP_XXXXXX`（密码 `espconfig`），提供配网接口
- 手机 App 连接该热点后，向 `http://192.168.4.1/config` 发送 WiFi 账号密码
- ESP 保存到 EEPROM 并重启，切 Station 模式连 WiFi，连接公共 MQTT broker（`broker.emqx.io:1883`）
- 订阅 `esp8266/<deviceId>/cmd`，收到 `{"pin":12}` 后把对应 GPIO 拉高 500ms 再拉低
- 发布状态到 `esp8266/<deviceId>/state`（心跳 + 按键确认）

## 引脚映射
| 按钮 | GPIO | 说明 |
|------|------|------|
| 1 | GPIO12 | 按下给 500ms 高脉冲 |
| 2 | GPIO13 | 按下给 500ms 高脉冲 |
| 3 | GPIO14 | 按下给 500ms 高脉冲 |
| 4 | GPIO16 | 按下给 500ms 高脉冲 |

> 注意：ESP8266 的 GPIO16 与深度睡眠唤醒共用，作为普通输出使用正常。若某些板子（如 NodeMCU D0=GPIO16）被占用，可在固件 `GPIOS[]` 数组里改。

## 编译与烧录
### 方式 A：Arduino IDE
1. 开发板管理器安装 `esp8266 by ESP8266 Community`
2. 工具 → 开发板选 `Generic ESP8266 Module`（或 `NodeMCU 1.0`）
3. 库管理器安装 `PubSubClient`
4. 打开 `esp8266_controller.ino`，选好端口与 Flash 大小（4MB），上传

### 方式 B：PlatformIO
```bash
pip install platformio
pio run -t upload        # 烧录
pio device monitor       # 看串口日志
```

### 方式 C：下载 GitHub Actions 预编译固件（无需本地编译环境）
本仓库已配置 GitHub Actions（`build-firmware.yml`），每次推送都会自动编译出固件
`esp8266-io-controller-0x00000.bin`，在网页上即可下载烧录，不必安装任何工具链：

1. 打开仓库 **Actions** 标签页 → 选 **Build ESP8266 Firmware** 工作流 → 进入一次运行
2. 底部 **Artifacts** 区下载 **`esp8266-firmware`**（内含 `.bin` 与 `.elf`）
   - 手机 GitHub App 不能直接下载产物，请用**手机浏览器（桌面模式）**或电脑下载
3. 把 ESP8266 进入**烧录模式**（按住 BOOT/FLASH 键 → 按一下 RST → 松开 BOOT），再烧录：
   - **命令行（esptool，任意系统）**：
     ```bash
     esptool.py write_flash --flash_mode dout --flash_freq 40m --flash_size 4MB 0x00000 esp8266-io-controller-0x00000.bin
     ```
   - **Windows 图形工具（Espressif Flash Download Tool）**：
     加载该 `.bin` 到地址 `0x00000`，**SPI MODE = DIO**，**FLASH SIZE = 4MB**，
     选好 COM 口后点 START。

   > ESP8266 没有 ESP32 那种分区表（不存在 bootloader.bin / partitions.bin），
   > PlatformIO 只产出一个 `firmware.bin`，**烧录地址固定为 0x0**，别按 ESP32 的 0x10000 烧。
   > 多数 ESP-12F / NodeMCU / Wemos D1 Mini 为 4MB Flash；若你的板子是 1MB，请把
   > `platformio.ini` 的 `board` 或 `board_build.flash_size` 改小后重新编译。

## 配网步骤（与 App 配套）
1. 上电后手机 WiFi 列表会出现 `ESP_XXXXXX` 热点，连上（密码 `espconfig`）
2. 打开手机 App → 自动进入「配网」页 → 选择该热点 → 输入你家 WiFi 的 SSID/密码 → 发送
3. ESP 重启后自动连网，App 自动切换到「控制」页，四个按钮即可远程控制

## v1.1 新增：运行状态与恢复出厂

设备联网后仍提供 HTTP 服务（`http://<设备IP>/...`，热点模式下是 `http://192.168.4.1/...`）：

| 接口 | 作用 |
|------|------|
| `GET /` | 接口说明页 |
| `GET /status` | 返回 deviceId、固件版本、当前模式、IP、RSSI、MQTT 连接状态、四路引脚电平 |
| `GET /reset` | 清除 WiFi 配置并重启，重新进入热点配网状态 |

另外，**按住 FLASH（GPIO0）键再上电并持续 2 秒**，也会清空配置（串口会打印
`[Boot] reset key held...`）。

## 重配网
App 控制页点「重新配网」只清除手机侧保存的设备ID；要让 ESP 重新开热点，需要访问
`/reset` 或用上面的 FLASH 键方式清除 ESP 里的 WiFi 配置。
串口监视器（115200）可以看到 deviceId 与连接状态。

## 自定义
- 脉冲宽度：改 `#define PULSE_MS 500`
- MQTT broker：改 `MQTT_SERVER` / `MQTT_PORT`
- 热点密码：改 `AP_PASSWORD`（App 端 `Constants.AP_PASSWORD` 要同步改）
- 引脚：改 `GPIOS[]` 数组（App 端 `Constants.GPIO_PINS` 顺序要同步改）
- 心跳间隔：`HEARTBEAT_MS`；遗嘱消息默认发布 `{"status":"offline"}`
