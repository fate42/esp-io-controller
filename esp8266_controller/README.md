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

## 配网步骤（与 App 配套）
1. 上电后手机 WiFi 列表会出现 `ESP_XXXXXX` 热点，连上（密码 `espconfig`）
2. 打开手机 App → 自动进入「配网」页 → 选择该热点 → 输入你家 WiFi 的 SSID/密码 → 发送
3. ESP 重启后自动连网，App 自动切换到「控制」页，四个按钮即可远程控制

## 重配网
App 控制页点「重新配网」会清除已存设备ID，ESP 下次启动会重新开热点。
也可在串口监视器看到 deviceId 与连接状态。

## 自定义
- 脉冲宽度：改 `#define PULSE_MS 500`
- MQTT broker：改 `MQTT_SERVER` / `MQTT_PORT`
- 热点密码：改 `AP_PASSWORD`
- 引脚：改 `GPIOS[]` 数组
