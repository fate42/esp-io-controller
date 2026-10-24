/*
 * ESP8266 四路 IO 脉冲控制器（公共 MQTT broker 方案）  v1.1.0
 * ------------------------------------------------------------------
 * 功能：
 *   1) 首次上电（或配置被清空）：开热点 ESP_XXXXXX（密码 espconfig），提供配网 HTTP 接口
 *   2) 手机 App 通过 http://192.168.4.1/config 下发家中 WiFi 账号密码
 *   3) 存入 EEPROM 后重启，切 Station 模式连 WiFi，并连接公共 MQTT broker
 *   4) 订阅 esp8266/<deviceId>/cmd，收到 {"pin":12} 后把对应 GPIO 拉高 500ms 再拉低
 *   5) 发布状态到 esp8266/<deviceId>/state（心跳 + 脉冲确认 + 遗嘱 offline）
 *
 * v1.1.0 修订（相对初版）：
 *   [1] EEPROM 增加魔数 "EC01" 校验：脏数据不再让设备陷入"永远连不上"的死循环
 *   [2] 恢复出厂通道：① 上电按住 FLASH(GPIO0) 键 2 秒  ② 任意模式访问 http://<ip>/reset
 *   [3] MQTT 遗嘱消息(LWT)：设备异常掉线时 broker 自动代发 {"status":"offline"}
 *   [4] Station 模式同样开放 HTTP 服务：/status 查看运行状态、/reset 重配网，无需重刷固件
 *   [5] 命令解析改为"定位 pin 键 → 跳过非数字 → 扫描数字"，兼容 {"pin":12} / 12 / {"pin": 12}
 *   [6] 设置 MQTT keepAlive=30s、缓冲区 256B；WiFi 重连阻塞期间仍然按时结束脉冲
 *
 * 硬件：ESP-12F / NodeMCU / Wemos D1 等任意 ESP8266
 * GPIO 映射：按钮1=GPIO12, 按钮2=GPIO13, 按钮3=GPIO14, 按钮4=GPIO16（GPIO0 预留为恢复出厂按键）
 *
 * 依赖库：ESP8266WiFi / ESP8266WebServer（开发板包自带）
 *         PubSubClient（Arduino 库管理器搜索 "PubSubClient" 安装，platformio.ini 已声明）
 *
 * 开发环境：Arduino IDE 选 "Generic ESP8266 Module" 或 "NodeMCU 1.0"；
 *           或 PlatformIO：pio run -t upload
 */

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <PubSubClient.h>
#include <EEPROM.h>

/* ====================== 可配置参数 ====================== */
#define FW_VERSION      "1.1.0"
#define AP_PREFIX       "ESP_"          // 热点名称/设备ID 前缀
#define AP_PASSWORD     "espconfig"     // 配网热点密码（App 端 Constants.AP_PASSWORD 必须一致）
#define MQTT_SERVER     "broker.emqx.io"
#define MQTT_PORT       1883
#define PULSE_MS        500             // 高电平脉冲宽度（毫秒）
#define CONNECT_TIMEOUT 15000           // Station 连接超时(ms)
#define HEARTBEAT_MS    10000           // 心跳间隔(ms)
#define MQTT_KEEPALIVE  30              // MQTT keepAlive(s)，需 < broker 超时
#define MQTT_BUFFER     256             // MQTT 收发缓冲区(字节)

#define EEPROM_SIZE     128
#define EEPROM_MAGIC    "EC01"          // 配置区魔数，用于判断 EEPROM 数据是否有效
#define MAGIC_ADDR      0               // 4 字节
#define SSID_ADDR       8               // WiFi SSID（最多 32 字节）
#define SSID_MAX        32
#define PASS_ADDR       48              // WiFi 密码（最多 63 字节）
#define PASS_MAX        63

#define RESET_PIN       0               // GPIO0 = NodeMCU 上的 FLASH 按键
#define RESET_HOLD_MS   2000            // 上电后按住多久触发恢复出厂

// 四个受控 GPIO（顺序即 App 按钮 1~4）
const int GPIOS[4] = {12, 13, 14, 16};

/* ====================== 全局对象 ====================== */
String          deviceId = "";
ESP8266WebServer server(80);
WiFiClient      espClient;
PubSubClient    mqtt(espClient);

// 非阻塞脉冲状态机（不用 delay，保证 500ms 精确且不卡住网络循环）
bool         pulseActive[4] = {false, false, false, false};
unsigned long pulseExpire[4] = {0, 0, 0, 0};

unsigned long lastHeartbeat = 0;
unsigned long lastReconnectAttempt = 0;

/* ====================== EEPROM 读写 ====================== */
String readEEPROM(int start, int maxLen) {
  String s = "";
  for (int i = 0; i < maxLen; i++) {
    char c = (char)EEPROM.read(start + i);
    if (c == 0 || c == (char)0xFF) break;
    s += c;
  }
  return s;
}

void writeEEPROM(int start, int maxLen, const String& s) {
  int n = s.length();
  if (n > maxLen) n = maxLen;          // 越界保护：宁可截断也不踩到相邻字段
  for (int i = 0; i < n; i++) EEPROM.write(start + i, s[i]);
  EEPROM.write(start + n, 0);
}

bool hasValidConfig() {
  for (int i = 0; i < 4; i++) {
    if (EEPROM.read(MAGIC_ADDR + i) != (uint8_t)EEPROM_MAGIC[i]) return false;
  }
  return readEEPROM(SSID_ADDR, SSID_MAX).length() > 0;
}

void saveConfig(const String& ssid, const String& password) {
  for (int i = 0; i < 4; i++) EEPROM.write(MAGIC_ADDR + i, (uint8_t)EEPROM_MAGIC[i]);
  writeEEPROM(SSID_ADDR, SSID_MAX, ssid);
  writeEEPROM(PASS_ADDR, PASS_MAX, password);
  EEPROM.commit();
}

// 恢复出厂：只清魔数即可让配置失效，同时把两段字符串也抹掉，避免残留
void clearConfig() {
  for (int i = 0; i < EEPROM_SIZE; i++) EEPROM.write(i, 0);
  EEPROM.commit();
  Serial.println("[EEPROM] configuration cleared");
}

/* ====================== 设备ID ====================== */
// 设备ID = "ESP_" + MAC 后 6 位十六进制，与热点名一致
String getMAC6() {
  byte mac[6];
  WiFi.macAddress(mac);
  char buf[7];
  snprintf(buf, sizeof(buf), "%02X%02X%02X", mac[3], mac[4], mac[5]);
  return String(buf);
}

/* ====================== MQTT 主题与发布 ====================== */
String cmdTopic()   { return "esp8266/" + deviceId + "/cmd"; }
String stateTopic() { return "esp8266/" + deviceId + "/state"; }

// retain=true 用于 online/offline 这类"持续状态"，让新订阅者立刻拿到最新值；
// 脉冲确认属于事件，不保留，避免重启后被误认为刚触发过
void publishState(int pin, const String& status, bool retain = false) {
  if (!mqtt.connected()) return;
  String payload = "{\"device\":\"" + deviceId + "\"";
  if (pin > 0) payload += ",\"pin\":" + String(pin);
  payload += ",\"status\":\"" + status + "\"}";
  mqtt.publish(stateTopic().c_str(), payload.c_str(), retain);
}

/* ====================== 脉冲处理 ====================== */
// 每次网络循环开头都调用，保证即使 WiFi 正在重连，500ms 到点也会立即拉低
void servicePulse() {
  unsigned long now = millis();
  for (int i = 0; i < 4; i++) {
    if (pulseActive[i] && (long)(now - pulseExpire[i]) >= 0) {
      digitalWrite(GPIOS[i], LOW);
      pulseActive[i] = false;
      Serial.printf("[IO] pin %d -> LOW\n", GPIOS[i]);
    }
  }
}

void triggerPulse(int pin) {
  for (int i = 0; i < 4; i++) {
    if (GPIOS[i] == pin) {
      // 同一路在脉冲期间再次触发：直接把截止时间后延，不产生嵌套
      digitalWrite(pin, HIGH);
      pulseActive[i] = true;
      pulseExpire[i] = millis() + PULSE_MS;
      publishState(pin, "pulsed");
      Serial.printf("[IO] pin %d -> HIGH for %d ms\n", pin, PULSE_MS);
      return;
    }
  }
  Serial.printf("[IO] rejected unknown pin %d\n", pin);
  publishState(pin, "badpin");
}

/* ====================== 命令解析 ====================== */
// 从 {"pin":12} / {"pin": 12} / 12 中提取 pin，失败返回 -1
int extractPin(const String& msg) {
  int start = 0;
  int keyIdx = msg.indexOf("\"pin\"");
  if (keyIdx >= 0) {
    int colon = msg.indexOf(':', keyIdx);
    if (colon < 0) return -1;
    start = colon + 1;
  }
  while (start < (int)msg.length() && !isDigit((unsigned char)msg[start])) {
    if (msg[start] != ' ' && msg[start] != '\t' && msg[start] != '"') return -1;
    start++;
  }
  long value = 0;
  bool got = false;
  while (start < (int)msg.length() && isDigit((unsigned char)msg[start])) {
    value = value * 10 + (msg[start] - '0');
    got = true;
    start++;
    if (value > 100) break;
  }
  return got ? (int)value : -1;
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String msg = "";
  for (unsigned int i = 0; i < length && i < MQTT_BUFFER; i++) msg += (char)payload[i];
  Serial.printf("[MQTT] %s : %s\n", topic, msg.c_str());

  int pin = extractPin(msg);
  if (pin > 0) triggerPulse(pin);
  else Serial.println("[MQTT] ignore message without valid pin");
}

void mqttReconnect() {
  if (mqtt.connected()) return;
  String clientId = "esp_" + deviceId;
  // 遗嘱消息：本设备异常断开时，broker 自动向 state 主题发布 offline（retain）
  String will = "{\"device\":\"" + deviceId + "\",\"status\":\"offline\"}";
  bool ok = mqtt.connect(clientId.c_str(), nullptr, nullptr,
                         stateTopic().c_str(), 0, true, will.c_str());
  if (ok) {
    mqtt.subscribe(cmdTopic().c_str());
    publishState(0, "online", true);
    Serial.println("[MQTT] connected & subscribed");
  } else {
    Serial.printf("[MQTT] connect failed, rc=%d (retry in 5s)\n", mqtt.state());
  }
}

/* ====================== WiFi 连接 ====================== */
bool connectToWiFi(const String& ssid, const String& password) {
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), password.c_str());
  Serial.printf("[WiFi] connecting to %s ...\n", ssid.c_str());
  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < CONNECT_TIMEOUT) {
    servicePulse();                 // 重连阻塞期间不能耽误脉冲结束
    delay(200);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("[WiFi] connected, IP=%s\n", WiFi.localIP().toString().c_str());
    return true;
  }
  Serial.println("[WiFi] connection failed/timeout");
  return false;
}

/* ====================== HTTP 接口 ====================== */
void handleConfig() {
  String ssid = server.arg("ssid");
  String password = server.arg("password");

  // 兼容 JSON 请求体：{"ssid":"...","password":"..."}
  if (ssid.length() == 0 && password.length() == 0) {
    String body = server.arg("plain");
    if (body.length() == 0) body = server.arg(0);   // 部分固件把原始体放在 arg(0)
    auto extract = [&](const char* key) -> String {
      int i = body.indexOf(key);
      if (i < 0) return "";
      int q1 = body.indexOf('"', i + strlen(key));
      if (q1 < 0) return "";
      int q2 = body.indexOf('"', q1 + 1);
      if (q2 < 0) return "";
      return body.substring(q1 + 1, q2);
    };
    ssid = extract("\"ssid\"");
    password = extract("\"password\"");
  }

  if (ssid.length() == 0) {
    server.send(400, "application/json", "{\"error\":\"missing ssid\"}");
    return;
  }

  saveConfig(ssid, password);
  Serial.printf("[Config] saved ssid=%s, rebooting\n", ssid.c_str());
  server.send(200, "application/json",
              "{\"status\":\"ok\",\"deviceId\":\"" + deviceId + "\"}");
  delay(800);
  ESP.restart();
}

void handleReset() {
  server.send(200, "application/json", "{\"status\":\"reset\",\"rebooting\":true}");
  delay(400);
  clearConfig();
  ESP.restart();
}

void handleStatus() {
  String json = "{\"device\":\"" + deviceId + "\"";
  json += ",\"fw\":\"" + String(FW_VERSION) + "\"";
  json += ",\"mode\":\"" + String(WiFi.getMode() == WIFI_AP ? "ap" : "sta") + "\"";
  json += ",\"ip\":\"" + WiFi.localIP().toString() + "\"";
  json += ",\"rssi\":" + String(WiFi.RSSI());
  json += ",\"mqtt\":" + String(mqtt.connected() ? "true" : "false");
  json += ",\"pins\":[";
  for (int i = 0; i < 4; i++) {
    if (i) json += ",";
    json += "{\"pin\":" + String(GPIOS[i]) + ",\"level\":" + String(digitalRead(GPIOS[i])) + "}";
  }
  json += "]}";
  server.send(200, "application/json", json);
}

void handleRoot() {
  server.send(200, "text/html",
    "<h3>ESP8266 IO Controller " FW_VERSION "</h3>"
    "<p>POST /config?ssid=..&amp;password=.. —— 下发 WiFi 配置</p>"
    "<p>GET&nbsp; /status —— 查看运行状态</p>"
    "<p>GET&nbsp; /reset —— 清除 WiFi 配置并重启（重新进入配网热点）</p>");
}

void setupRoutes() {
  server.on("/", handleRoot);
  server.on("/config", HTTP_POST, handleConfig);
  server.on("/config", HTTP_GET, []() {
    server.send(200, "application/json", "{\"deviceId\":\"" + deviceId + "\"}");
  });
  server.on("/status", handleStatus);
  server.on("/reset", handleReset);
  server.onNotFound(handleRoot);
  server.begin();
}

void startAP() {
  WiFi.mode(WIFI_AP);
  deviceId = AP_PREFIX + getMAC6();
  WiFi.softAP(deviceId.c_str(), AP_PASSWORD);
  Serial.printf("[AP] started SSID=%s  pwd=%s  IP=192.168.4.1\n",
                deviceId.c_str(), AP_PASSWORD);
  setupRoutes();
}

void startStation() {
  String ssid = readEEPROM(SSID_ADDR, SSID_MAX);
  String password = readEEPROM(PASS_ADDR, PASS_MAX);
  if (!connectToWiFi(ssid, password)) {
    Serial.println("[Boot] WiFi failed -> fall back to AP mode");
    startAP();
    return;
  }
  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
  mqtt.setCallback(mqttCallback);
  mqtt.setKeepAlive(MQTT_KEEPALIVE);
  mqtt.setBufferSize(MQTT_BUFFER);
  mqttReconnect();
  setupRoutes();                        // 联网后仍可用 http://<ip>/status 与 /reset
  Serial.printf("[STA] web console ready: http://%s/status\n",
                WiFi.localIP().toString().c_str());
}

/* ====================== Setup ====================== */
void setup() {
  Serial.begin(115200);
  EEPROM.begin(EEPROM_SIZE);

  for (int i = 0; i < 4; i++) {
    pinMode(GPIOS[i], OUTPUT);
    digitalWrite(GPIOS[i], LOW);
  }

  deviceId = AP_PREFIX + getMAC6();
  Serial.printf("\n=== ESP8266 4-IO Pulse Controller %s ===\ndeviceId = %s\n",
                FW_VERSION, deviceId.c_str());

  // 恢复出厂：上电时按住 FLASH(GPIO0) 键 2 秒
  pinMode(RESET_PIN, INPUT_PULLUP);
  if (digitalRead(RESET_PIN) == LOW) {
    Serial.println("[Boot] reset key held, keep pressing 2s to clear config...");
    unsigned long t0 = millis();
    while (digitalRead(RESET_PIN) == LOW && millis() - t0 < RESET_HOLD_MS) delay(100);
    if (digitalRead(RESET_PIN) == LOW) {
      clearConfig();
      Serial.println("[Boot] factory reset done, rebooting");
      delay(300);
      ESP.restart();
    }
    Serial.println("[Boot] released too early, normal boot");
  }

  if (!hasValidConfig()) {
    Serial.println("[Boot] no valid WiFi config -> AP mode");
    startAP();
    return;
  }

  startStation();
}

/* ====================== Loop ====================== */
void loop() {
  servicePulse();                       // 先处理脉冲结束，任何情况下都不拖延
  server.handleClient();                // AP / STA 两种模式都监听 HTTP

  if (WiFi.getMode() == WIFI_AP) return;

  if (WiFi.status() != WL_CONNECTED) {
    String ssid = readEEPROM(SSID_ADDR, SSID_MAX);
    String password = readEEPROM(PASS_ADDR, PASS_MAX);
    if (!connectToWiFi(ssid, password)) {
      startAP();
      return;
    }
    mqtt.setServer(MQTT_SERVER, MQTT_PORT);
    mqtt.setCallback(mqttCallback);
    mqtt.setKeepAlive(MQTT_KEEPALIVE);
    mqtt.setBufferSize(MQTT_BUFFER);
  }

  if (!mqtt.connected()) {
    unsigned long now = millis();
    if (now - lastReconnectAttempt > 5000) {
      lastReconnectAttempt = now;
      mqttReconnect();
    }
  }
  mqtt.loop();

  unsigned long now = millis();
  if (now - lastHeartbeat > HEARTBEAT_MS) {
    lastHeartbeat = now;
    publishState(0, "online", true);
  }

  yield();
}
