/*
 * ESP8266 四路 IO 脉冲控制器（D 模式：公共 MQTT broker）
 * ---------------------------------------------------------
 * 功能：
 *   1) 首次上电：开热点 ESP_XXXXXX（密码 espconfig），提供配网 HTTP 接口
 *   2) 手机 App 通过 http://192.168.4.1/config 下发 WiFi 账号密码
 *   3) 保存到 EEPROM 后重启，切 Station 模式连 WiFi，并连接公共 MQTT broker
 *   4) 订阅 esp8266/<deviceId>/cmd，收到 {"pin":12} 后把对应 GPIO 拉高 500ms 再拉低
 *   5) 发布状态到 esp8266/<deviceId>/state（心跳 + 按键确认）
 *
 * 硬件：ESP-12F / NodeMCU / Wemos D1 等任意 ESP8266
 * GPIO 映射：按钮1=GPIO12, 按钮2=GPIO13, 按钮3=GPIO14, 按钮4=GPIO16
 *
 * 依赖库（Arduino ESP8266 开发板包自带）：
 *   - ESP8266WiFi
 *   - ESP8266WebServer
 *   - PubSubClient  (库管理器搜索 "PubSubClient" 安装)
 *
 * 开发环境：Arduino IDE 选中板子 "Generic ESP8266 Module" 或 "NodeMCU 1.0"
 */

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <PubSubClient.h>
#include <EEPROM.h>

/* ====================== 可配置参数 ====================== */
#define AP_PREFIX       "ESP_"          // 热点/设备ID 前缀
#define AP_PASSWORD     "espconfig"     // 配网热点密码（App 已知）
#define MQTT_SERVER     "broker.emqx.io"
#define MQTT_PORT       1883
#define PULSE_MS        500             // 高电平脉冲宽度（毫秒）
#define EEPROM_SIZE     128
#define SSID_ADDR       0               // WiFi SSID 存放地址 (max 32)
#define PASS_ADDR       64              // WiFi 密码 存放地址 (max 63)
#define CONNECT_TIMEOUT 20000          // Station 连接超时(ms)
#define HEARTBEAT_MS    10000           // 心跳间隔(ms)

// 四个受控 GPIO（顺序即按钮 1~4）
const int GPIOS[4] = {12, 13, 14, 16};

/* ====================== 全局变量 ====================== */
String deviceId   = "";
ESP8266WebServer server(80);
WiFiClient       espClient;
PubSubClient     mqtt(espClient);

// 非阻塞脉冲状态
bool        pulseActive[4] = {false, false, false, false};
unsigned long pulseExpire[4] = {0, 0, 0, 0};

unsigned long lastHeartbeat = 0;
unsigned long lastReconnectAttempt = 0;

/* ====================== EEPROM 读写 ====================== */
String readEEPROM(int start, int maxLen) {
  String s = "";
  for (int i = 0; i < maxLen; i++) {
    char c = EEPROM.read(start + i);
    if (c == 0 || c == 255) break;
    s += c;
  }
  return s;
}

void writeEEPROM(int start, const String& s) {
  for (int i = 0; i < s.length(); i++) EEPROM.write(start + i, s[i]);
  EEPROM.write(start + s.length(), 0);
  EEPROM.commit();
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

/* ====================== MQTT 主题 ====================== */
String cmdTopic()   { return "esp8266/" + deviceId + "/cmd"; }
String stateTopic() { return "esp8266/" + deviceId + "/state"; }

void publishState(int pin, const String& status) {
  String payload = "{\"device\":\"" + deviceId + "\"";
  if (pin > 0) payload += ",\"pin\":" + String(pin);
  payload += ",\"status\":\"" + status + "\"}";
  mqtt.publish(stateTopic().c_str(), payload.c_str());
}

/* ====================== 脉冲触发 ====================== */
void triggerPulse(int pin) {
  for (int i = 0; i < 4; i++) {
    if (GPIOS[i] == pin) {
      digitalWrite(pin, HIGH);
      pulseActive[i]  = true;
      pulseExpire[i]  = millis() + PULSE_MS;
      publishState(pin, "pulsed");
      Serial.printf("[IO] pin %d -> HIGH for %d ms\n", pin, PULSE_MS);
      return;
    }
  }
  Serial.printf("[IO] unknown pin %d\n", pin);
}

/* ====================== MQTT 回调 ====================== */
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String msg = "";
  for (unsigned int i = 0; i < length; i++) msg += (char)payload[i];
  Serial.printf("[MQTT] %s : %s\n", topic, msg.c_str());

  int pin = -1;
  int idx = msg.indexOf("pin");
  if (idx >= 0) {
    int colon = msg.indexOf(':', idx);
    if (colon >= 0) {
      String num = "";
      int k = colon + 1;
      while (k < msg.length() && (isDigit(msg[k]) || msg[k] == '-')) { num += msg[k]; k++; }
      pin = num.toInt();
    }
  } else {
    pin = msg.toInt();   // 兼容纯数字载荷
  }

  if (pin > 0) triggerPulse(pin);
}

void mqttReconnect() {
  if (mqtt.connected()) return;
  String clientId = "esp_" + deviceId;
  if (mqtt.connect(clientId.c_str())) {
    mqtt.subscribe(cmdTopic().c_str());
    publishState(0, "online");
    Serial.println("[MQTT] connected & subscribed");
  } else {
    Serial.printf("[MQTT] connect failed, rc=%d\n", mqtt.state());
  }
}

/* ====================== WiFi 连接 ====================== */
bool connectToWiFi(const String& ssid, const String& password) {
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), password.c_str());
  Serial.printf("[WiFi] connecting to %s ...\n", ssid.c_str());
  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < CONNECT_TIMEOUT) {
    delay(300);
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

/* ====================== 配网 HTTP 接口 ====================== */
void handleConfig() {
  // 支持 form-urlencoded: ssid=xxx&password=yyy
  String ssid = server.arg("ssid");
  String password = server.arg("password");

  // 兼容 JSON 体：简单提取 "ssid":"..." 与 "password":"..."
  if (ssid == "" && password == "") {
    String body = server.arg("plain");
    if (body == "") body = server.arg(0);   // 某些固件 POST 原始体在 arg(0)
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

  if (ssid == "") {
    server.send(400, "application/json", "{\"error\":\"missing ssid\"}");
    return;
  }

  writeEEPROM(SSID_ADDR, ssid);
  writeEEPROM(PASS_ADDR, password);
  Serial.printf("[Config] saved ssid=%s, rebooting\n", ssid.c_str());
  server.send(200, "application/json",
              "{\"status\":\"ok\",\"deviceId\":\"" + deviceId + "\"}");
  delay(1000);
  ESP.restart();
}

void startAP() {
  WiFi.mode(WIFI_AP);
  deviceId = AP_PREFIX + getMAC6();
  WiFi.softAP(deviceId.c_str(), AP_PASSWORD);
  Serial.printf("[AP] started SSID=%s  pwd=%s  IP=192.168.4.1\n",
                deviceId.c_str(), AP_PASSWORD);

  server.on("/config", HTTP_POST, handleConfig);
  server.on("/config", HTTP_GET, []() {
    server.send(200, "application/json", "{\"deviceId\":\"" + deviceId + "\"}");
  });
  server.onNotFound([]() {
    server.send(200, "text/html",
      "<h3>ESP8266 Config Server</h3>"
      "<p>POST /config with ssid & password (form-urlencoded or JSON)</p>");
  });
  server.begin();
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
  Serial.println("\n=== ESP8266 4-IO Pulse Controller ===");
  Serial.printf("deviceId = %s\n", deviceId.c_str());

  String ssid = readEEPROM(SSID_ADDR, 32);
  String password = readEEPROM(PASS_ADDR, 63);

  if (ssid.length() == 0) {
    Serial.println("[Boot] no WiFi configured -> AP mode");
    startAP();
    return;
  }

  if (connectToWiFi(ssid, password)) {
    mqtt.setServer(MQTT_SERVER, MQTT_PORT);
    mqtt.setCallback(mqttCallback);
    mqttReconnect();
  } else {
    Serial.println("[Boot] WiFi failed -> fall back to AP mode");
    startAP();
  }
}

/* ====================== Loop ====================== */
void loop() {
  // AP 配网模式：只处理 HTTP
  if (WiFi.getMode() == WIFI_AP) {
    server.handleClient();
    return;
  }

  // Station + MQTT 模式
  if (WiFi.status() != WL_CONNECTED) {
    String ssid = readEEPROM(SSID_ADDR, 32);
    String password = readEEPROM(PASS_ADDR, 63);
    if (!connectToWiFi(ssid, password)) {
      startAP();
      return;
    }
    mqtt.setServer(MQTT_SERVER, MQTT_PORT);
    mqtt.setCallback(mqttCallback);
  }

  if (!mqtt.connected()) {
    unsigned long now = millis();
    if (now - lastReconnectAttempt > 5000) {
      lastReconnectAttempt = now;
      mqttReconnect();
    }
  }
  mqtt.loop();

  // 处理脉冲结束（非阻塞）
  unsigned long now = millis();
  for (int i = 0; i < 4; i++) {
    if (pulseActive[i] && (long)(now - pulseExpire[i]) >= 0) {
      digitalWrite(GPIOS[i], LOW);
      pulseActive[i] = false;
      Serial.printf("[IO] pin %d -> LOW\n", GPIOS[i]);
    }
  }

  // 心跳
  if (now - lastHeartbeat > HEARTBEAT_MS) {
    lastHeartbeat = now;
    if (mqtt.connected()) publishState(0, "online");
  }

  yield();
}
