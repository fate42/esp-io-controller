/*
 * ESP8266 四路 IO 脉冲控制器（公共 MQTT broker 方案）  v1.3.0
 * ------------------------------------------------------------------
 * 功能：
 *   1) 首次上电（或配置被清空）：开热点 ESP_XXXXXX（密码 espconfig），提供配网 HTTP 接口
 *   2) 手机 App 通过 http://192.168.4.1/config 下发家中 WiFi 账号密码
 *   3) 存入 EEPROM 后重启，切 Station 模式连 WiFi，并连接公共 MQTT broker
 *   4) 订阅 io/<deviceId>/cmd，收到 {"pin":12,"key":"..."} 后校验密钥，通过才把对应
 *      GPIO 拉高 500ms 再拉低
 *   5) 发布状态到 io/<deviceId>/state（心跳 + 脉冲确认 + 遗嘱 offline）
 *
 * v1.3.0 安全加固（相对 v1.1.0）：
 *   [1] 设备身份随机化：deviceId 由可猜测的 "ESP_"+MAC后6位 改为 16 位十六进制随机串，
 *       存在 EEPROM 里，与热点名(ESP_xxxxxx)解耦——热点名只用于配网发现，不再泄露身份。
 *   [2] 配网即"配对"：首次 /config 时生成 32 位十六进制随机密钥(secretKey)，写回 App，
 *       命令 payload 必须带 "key" 且匹配才执行；密钥只在本地配网链路下发，绝不走公网。
 *   [3] MQTT 主题前缀由 esp8266/ 改为 io/，叠加随机 deviceId 后主题不可猜测，
 *       在公共 broker 上实现"主题隔离"（无密钥者即便猜到设备也下不了命令）。
 *   [4] /status 增加 "secure" 字段：true=已注册密钥并强制校验，false=遗留设备兼容模式
 *       （接受任意命令，建议恢复出厂后重新配网以注册密钥）。
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

/* ---- 第三方库 ---- */
#include <ESP8266WiFi.h>      // 提供 WiFi(STA/AP)、softAP、RSSI、MAC 等底层连接能力
#include <ESP8266WebServer.h>  // 板载 HTTP 服务器：配网 /status /reset 接口都靠它
#include <PubSubClient.h>      // MQTT 客户端，连接公共 broker 并收发命令/状态
#include <EEPROM.h>            // ESP8266 的 4KB 模拟 EEPROM（flash 模拟），存 WiFi 与配对身份

/* ====================== 可配置参数 ====================== */
#define FW_VERSION      "1.3.0"
#define AP_PREFIX       "ESP_"          // 热点名称前缀：仅用于"配网发现"，不再充当 MQTT 身份
#define AP_PASSWORD     "espconfig"     // 配网热点密码（App 端 Constants.AP_PASSWORD 必须一字不差）
#define MQTT_SERVER     "broker.emqx.io"// 公共 MQTT broker（零成本、免自建）；生产可换自建带鉴权的 broker
#define MQTT_PORT       1883            // 明文 MQTT 端口；如需加密走 8883(TLS)，但要改连接代码
#define TOPIC_PREFIX    "io/"           // MQTT 主题前缀：随机 deviceId 接在后面，使完整主题不可猜测
#define PULSE_MS        500             // 高电平脉冲宽度（毫秒）：继电器/电磁阀典型吸合保持时间
#define CONNECT_TIMEOUT 15000           // Station 模式连家里 WiFi 的最长等待(ms)，超时则回退 AP
#define HEARTBEAT_MS    10000           // 心跳间隔(ms)：定期 publish online(retain)，证明"我还在线"
#define MQTT_KEEPALIVE  30              // MQTT keepAlive(s)：必须小于 broker 的超时，否则被踢；配网时重连靠它
#define MQTT_BUFFER     256             // MQTT 收发缓冲区(字节)：命令/状态 payload 都很短，256B 绰绰有余

// 安全相关长度（十六进制字符串长度）：deviceId 16 位(64bit) / 密钥 32 位(128bit)
#define DEVID_HEX       16
#define KEY_HEX         32

/* ---- EEPROM 字节布局（共 512 字节，按 4 字节对齐留余量）----
 *  ┌─────────┬────────────┬────────────┬─────────────┬──────────────┐
 *  │ 0..3    │ 8..39      │ 48..110    │ 112..127     │ 129..160      │
 *  │ MAGIC   │ SSID(≤32)  │ PASS(≤63)  │ DEVID(≤16)  │ KEY(≤32)      │
 *  │ "EC01"  │ WiFi 账号  │ WiFi 密码  │ 随机设备ID  │ 随机配对密钥  │
 *  └─────────┴────────────┴────────────┴─────────────┴──────────────┘
 *  - 各字符串字段以 0x00 结尾，写入时越界自动截断保护相邻字段
 *  - DEVID/KEY 仅在 v1.3 起配网时写入；老固件升级上来这两段为空 → 走兼容模式
 *  - 注意 4~7、40~47、111、128、161~511 为保留/填充，避免字段边界重叠
 */
#define EEPROM_SIZE     512
#define EEPROM_MAGIC    "EC01"          // 配置区魔数：用来区分"已写过配置"与"全新/被清零"
#define MAGIC_ADDR      0               // 4 字节：魔数存放起点
#define SSID_ADDR       8               // WiFi SSID 起点（最多 32 字节，含结束符）
#define SSID_MAX        32
#define PASS_ADDR       48              // WiFi 密码起点（最多 63 字节，含结束符）
#define PASS_MAX        63
#define DEVID_ADDR      112             // 随机 deviceId 起点（16 位十六进制 + 结束符）
#define DEVID_MAX       16
#define KEY_ADDR        129             // 随机 secretKey 起点（32 位十六进制 + 结束符）
#define KEY_MAX         32

#define RESET_PIN       0               // GPIO0：NodeMCU/ESP-12F 板上 FLASH 按键，上电按住即恢复出厂
#define RESET_HOLD_MS   2000            // 上电后需持续按住多久才真正清配置（防误触）

// 四个受控 GPIO：数组下标 0~3 依次对应 App 的"按钮1~4"
// 选 12/13/14/16 是因为它们都可用作普通 IO 且不与 GPIO0/2/15 的启动 strapping 冲突
const int GPIOS[4] = {12, 13, 14, 16};

/* ====================== 全局对象 ====================== */
String          deviceId = "";         // 设备身份：AP 模式=ESP_+MAC6；配网后=EEPROM 里的随机串
String          deviceKey = "";        // 配对密钥：配网时生成、命令校验用；为空=遗留兼容模式(不校验)
ESP8266WebServer server(80);           // HTTP 服务器监听 80 端口（AP/STA 两种模式都用）
WiFiClient      espClient;             // 底层 TCP 客户端，交给 PubSubClient 复用
PubSubClient    mqtt(espClient);       // MQTT 客户端对象（连接/发布/订阅/回调都经它）

// 非阻塞脉冲状态机：不用 delay() 阻塞，保证 500ms 精确且不卡住 WiFi/MQTT 循环
bool         pulseActive[4] = {false, false, false, false}; // 每一路是否正处于"高电平"中
unsigned long pulseExpire[4] = {0, 0, 0, 0};                // 每一路高电平应结束的绝对时刻(millis)

unsigned long lastHeartbeat = 0;       // 上次发心跳的时间戳，用于节流
unsigned long lastReconnectAttempt = 0;// 上次尝试重连 MQTT 的时间戳，避免无脑重试打爆 broker

/* ====================== EEPROM 读写 ====================== */
// 从 start 起读出最多 maxLen 个字符，遇到 0x00(写过的结束符)或 0xFF(未写过)即停
// 返回 C++ String（注意：仅适合短字符串，频繁拼接有少量堆开销，本场景无所谓）
String readEEPROM(int start, int maxLen) {
  String s = "";
  for (int i = 0; i < maxLen; i++) {
    char c = (char)EEPROM.read(start + i);
    if (c == 0 || c == (char)0xFF) break;  // 0x00=有效数据结束；0xFF=该字节从未写过
    s += c;
  }
  return s;
}

// 写入字符串并在末尾补 0x00 结束符；长度超过 maxLen 时只截取到 maxLen（含结束符），保护相邻字段
// 注意：只 write 不 commit，调用方需自行 EEPROM.commit() 才会真正落盘
void writeEEPROM(int start, int maxLen, const String& s) {
  int n = s.length();
  if (n > maxLen) n = maxLen;          // 越界保护：宁可截断也不踩到相邻字段
  for (int i = 0; i < n; i++) EEPROM.write(start + i, s[i]);
  EEPROM.write(start + n, 0);
}

// 配置有效性判断：① 魔数 4 字节必须全对；② SSID 段非空。两者都满足才算"已配过网"
// 用魔数而非"SSID 非空"做主键，是因为老芯片 EEPROM 残留杂数据可能 SSID 段恰好非空
bool hasValidConfig() {
  for (int i = 0; i < 4; i++) {
    if (EEPROM.read(MAGIC_ADDR + i) != (uint8_t)EEPROM_MAGIC[i]) return false;
  }
  return readEEPROM(SSID_ADDR, SSID_MAX).length() > 0;
}

// 写 WiFi 配置：先落魔数(标记已配置)，再写 SSID/密码，最后 commit 一次性刷入 flash
void saveConfig(const String& ssid, const String& password) {
  for (int i = 0; i < 4; i++) EEPROM.write(MAGIC_ADDR + i, (uint8_t)EEPROM_MAGIC[i]);
  writeEEPROM(SSID_ADDR, SSID_MAX, ssid);
  writeEEPROM(PASS_ADDR, PASS_MAX, password);
  EEPROM.commit();
}

// 恢复出厂：整片 EEPROM 清零（顺带抹掉 SSID/PASS/DEVID/KEY，避免残留被误用），commit 落盘
void clearConfig() {
  for (int i = 0; i < EEPROM_SIZE; i++) EEPROM.write(i, 0);
  EEPROM.commit();
  Serial.println("[EEPROM] configuration cleared");
}

/* ====================== 设备ID ====================== */
// 取 MAC 后 3 字节(6 位十六进制)拼成热点名后缀。
// 仅用于"配网发现"——手机连热点时靠名字认设备；与安全身份(deviceId)解耦，
// 因为 MAC 是公开的、可被扫到，绝不能拿它当控制器身份。
String getMAC6() {
  byte mac[6];
  WiFi.macAddress(mac);
  char buf[7];
  snprintf(buf, sizeof(buf), "%02X%02X%02X", mac[3], mac[4], mac[5]);
  return String(buf);
}

/* ====================== 随机数与配对身份 ====================== */
// 生成 n 位十六进制随机串（deviceId 16 位 / secretKey 32 位）。
// 安全边界说明：密钥只在本地配网链路(192.168.4.1)下发、绝不进公网，且 MQTT 主题本身随机不可猜测，
// 因此用软件 PRNG 多熵源播种已足以抵御"远程盲猜 128 位 token"的攻击。
// 若要做到防本地物理侧信道(如电源毛刺)，需改用硬件 TRNG，对遥控类场景非必需。
void seedRandom() {
  static bool seeded = false;
  if (seeded) return;                  // 只播种一次，避免每次生成都重置导致序列可复现
  // 把多个弱随机但彼此独立的熵源异或起来：芯片唯一 ID + 启动时间 + 微秒计时 + 悬空 ADC 噪声
  uint32_t s = ESP.getChipId() ^ (millis() << 16) ^ micros() ^
               ((uint32_t)analogRead(0) << 8);
  randomSeed(s);
  seeded = true;
}

// 输出长度为 chars 的全小写十六进制串，每位 0~255 的随机字节转成 2 个 hex 字符
//
// 【v1.4 修复】原来循环 chars 次、每次产出 2 个字符，实际长度是 2*chars：
//   genHex(16) 生成 32 位，而 EEPROM 的 DEVID_MAX=16 只存前 16 位 →
//   配网响应回给手机的是 32 位、设备重启后读到的是 16 位，MQTT 主题（io/<id>/state）
//   两端不一致，手机永远收不到状态、命令也发不到设备（表现为"串口已连接、App 却显示无响应"）。
//   同理密钥 64 位 vs 存 32 位会让鉴权必然 denied。
//   这里改为按 chars/2 次生成，保证长度严格等于 chars，与 DEVID_MAX/KEY_MAX 对齐。
String genHex(int chars) {
  seedRandom();
  String s = "";
  char h[3];
  int n = (chars + 1) / 2;             // 向上取整，保证长度不小于 chars（chars 为奇数时多补 1 位）
  for (int i = 0; i < n; i++) {
    snprintf(h, sizeof(h), "%02x", (uint8_t)random(0, 256));
    s += h;
  }
  return s;
}

/* ====================== MQTT 主题与发布 ====================== */
// 命令主题：手机往这里发 {"pin":X,"key":"..."}；状态主题：设备往这里发状态。
// 完整形态如 io/ a1b2c3d4e5f6 /cmd —— 前半段随机不可猜测，是安全模型的第一道墙。
String cmdTopic()   { return String(TOPIC_PREFIX) + deviceId + "/cmd"; }
String stateTopic() { return String(TOPIC_PREFIX) + deviceId + "/state"; }

// 统一状态发布：拼出 {"device":...,"pin":...,"status":...}。
// retain=true 用于 online/offline 这类"持续状态"，让新订阅者立刻拿到最新值(比如刚进 App 就知在线)；
// 脉冲确认属于一次性事件，用 retain=false，避免重启后被 broker 当成"刚触发过"而误判。
void publishState(int pin, const String& status, bool retain = false) {
  if (!mqtt.connected()) return;       // 没连上就直接丢，不缓存（状态消息允许丢失，心跳会补）
  String payload = "{\"device\":\"" + deviceId + "\"";
  if (pin > 0) payload += ",\"pin\":" + String(pin);
  payload += ",\"status\":\"" + status + "\"}";
  mqtt.publish(stateTopic().c_str(), payload.c_str(), retain);
}

/* ====================== 脉冲处理 ====================== */
// 非阻塞"拉低"巡检：每次网络循环开头都调用，保证即使 WiFi 正在重连，
// 500ms 到点也会立即把引脚拉回 LOW，不会因网络阻塞而延长脉冲（否则继电器粘连）。
void servicePulse() {
  unsigned long now = millis();
  for (int i = 0; i < 4; i++) {
    if (pulseActive[i] && (long)(now - pulseExpire[i]) >= 0) {  // 用小技巧把毫秒回绕也处理掉
      digitalWrite(GPIOS[i], LOW);
      pulseActive[i] = false;
      Serial.printf("[IO] pin %d -> LOW\n", GPIOS[i]);
    }
  }
}

// 触发一路脉冲：先判断 pin 是否属于受控 GPIO 集合，是则拉高并记录"到期时刻"，
// 拉高瞬间即回报 pulsed（让 App 立刻有反馈），实际拉低由 servicePulse 在到期时完成。
void triggerPulse(int pin) {
  for (int i = 0; i < 4; i++) {
    if (GPIOS[i] == pin) {
      // 同一路在脉冲期间再次触发：直接把截止时间后延，不产生嵌套/重复定时器
      digitalWrite(pin, HIGH);
      pulseActive[i] = true;
      pulseExpire[i] = millis() + PULSE_MS;
      publishState(pin, "pulsed");
      Serial.printf("[IO] pin %d -> HIGH for %d ms\n", pin, PULSE_MS);
      return;
    }
  }
  // 不在白名单里的 pin（命令里写错或恶意试探）→ 拒绝并回报 badpin
  Serial.printf("[IO] rejected unknown pin %d\n", pin);
  publishState(pin, "badpin");
}

/* ====================== 命令解析 ====================== */
// 从多种写法里提取 pin：{"pin":12} / {"pin": 12} / 裸数字 12 都兼容，失败返回 -1。
// 设计原则：不依赖严格 JSON 解析（ArduinoJson 会吃内存），用轻量字符串定位即可，足够本场景用。
int extractPin(const String& msg) {
  int start = 0;
  int keyIdx = msg.indexOf("\"pin\"");          // 优先按 JSON 键定位，避免误读到别处的数字
  if (keyIdx >= 0) {
    int colon = msg.indexOf(':', keyIdx);
    if (colon < 0) return -1;                   // 有键无冒号，格式坏，直接放弃
    start = colon + 1;
  }
  // 跳过键后到数字之间的空白/引号（容忍 {"pin": 12} 里的空格）
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
    if (value > 100) break;                     // 上限保护：受控 GPIO 号远小于 100，超出视为异常截断
  }
  return got ? (int)value : -1;
}

// 从 {"pin":12,"key":"abcd..."} 中提取 key 字段（取紧跟 "key" 后的引号串）。
// 缺失或不完整返回空串——缺失即代表"没带密钥"，安全模式设备会据此拒绝。
String extractKey(const String& msg) {
  int k = msg.indexOf("\"key\"");
  if (k < 0) return "";
  int q1 = msg.indexOf('"', k + 5);             // k+5 跳过 "key" 这 5 个字符后再找左引号
  if (q1 < 0) return "";
  int q2 = msg.indexOf('"', q1 + 1);
  if (q2 < 0) return "";
  return msg.substring(q1 + 1, q2);
}

// MQTT 消息回调：一条命令进来的唯一入口。
// 处理逻辑：① 先把 payload 收成 String（截断到 MQTT_BUFFER 防溢出）；
//          ② 安全校验（见下）；③ 解析 pin 并触发脉冲或忽略。
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String msg = "";
  for (unsigned int i = 0; i < length && i < MQTT_BUFFER; i++) msg += (char)payload[i];
  Serial.printf("[MQTT] %s : %s\n", topic, msg.c_str());

  // 安全校验（v1.3 核心）：仅当本机已注册密钥(deviceKey 非空)时才强制校验。
  // 校验不通过 → 回 denied 并直接返回，绝不触发脉冲。带密钥者即使猜到主题也下不了命令。
  if (deviceKey.length() > 0) {
    if (extractKey(msg) != deviceKey) {
      Serial.println("[MQTT] rejected: key mismatch / missing");
      publishState(0, "denied");
      return;
    }
  }
  // 注意：deviceKey 为空说明是 v1.3 之前的老设备(没注册过密钥)，保持兼容——接受任意命令。

  int pin = extractPin(msg);
  if (pin > 0) triggerPulse(pin);
  else Serial.println("[MQTT] ignore message without valid pin");
}

void mqttReconnect() {
  if (mqtt.connected()) return;                 // 已连则跳过，避免重复订阅
  String clientId = "esp_" + deviceId;          // 客户端 ID：设备级唯一即可，broker 不据此鉴权
  // 遗嘱消息(LWT, Last Will and Testament)：在 connect 时注册。
  // 含义：若本设备"非正常断开"(掉电/看门狗/网络塌了)，broker 会自动代发这条 offline(retain)，
  // 让 App 立刻知道设备不在了——比单纯靠心跳超时更快、更可靠。
  String will = "{\"device\":\"" + deviceId + "\",\"status\":\"offline\"}";
  bool ok = mqtt.connect(clientId.c_str(), nullptr, nullptr,
                         stateTopic().c_str(), 0, true, will.c_str());
  if (ok) {
    mqtt.subscribe(cmdTopic().c_str());         // 连上第一件事：订阅自己的命令主题
    publishState(0, "online", true);            // 报到：online(retain) 让此时订阅者马上看到在线
    Serial.println("[MQTT] connected & subscribed");
  } else {
    // rc 是 PubSubClient 的错误码(负数)，常见：-2 连接失败 / -4 握手失败 / -5 未授权
    Serial.printf("[MQTT] connect failed, rc=%d (retry in 5s)\n", mqtt.state());
  }
}

/* ====================== WiFi 连接 ====================== */
// 阻塞式连接：最多等 CONNECT_TIMEOUT(15s)。期间每 200ms 让出一次并巡检脉冲，
// 避免"连 WiFi 的长循环把正在进行的脉冲卡住"。超时返回 false，由调用方决定回退 AP。
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
// 配网接口：手机 App 连上 ESP 热点后调用，把家里 WiFi 账号密码交过来。
// 这是"配网即配对"的安全入口——首次成功配网时会生成并回传 deviceId+密钥。
void handleConfig() {
  // 支持两种传参：① 表单/Query：?ssid=..&password=..  ② JSON 请求体：{"ssid":..,"password":..}
  String ssid = server.arg("ssid");
  String password = server.arg("password");

  // 兼容 JSON 请求体：{"ssid":"...","password":"..."}
  if (ssid.length() == 0 && password.length() == 0) {
    String body = server.arg("plain");            // 部分库把原始体放在 "plain" 参数
    if (body.length() == 0) body = server.arg(0); // 实在没有就取 arg(0)（POST 原始体）
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
    server.send(400, "application/json", "{\"error\":\"missing ssid\"}");  // 没给 SSID 直接拒绝
    return;
  }

  // 配网即"配对"：首次下发 WiFi 时生成随机 deviceId + secretKey 并固化到 EEPROM；
  // 已存在则沿用（仅改 WiFi 不换密钥），避免误改配对关系。
  // 关键安全点：密钥只在 192.168.4.1 这条本地链路回传给手机，绝不通过公网 MQTT 传递。
  String id = readEEPROM(DEVID_ADDR, DEVID_MAX);
  String key = readEEPROM(KEY_ADDR, KEY_MAX);
  if (id.length() == 0) {
    id = genHex(DEVID_HEX);
    key = genHex(KEY_HEX);
    writeEEPROM(DEVID_ADDR, DEVID_MAX, id);   // 注意：DEVID/KEY 这两段写的是 EEPROM 暂存区，
    writeEEPROM(KEY_ADDR, KEY_MAX, key);      // 还没 commit；下面 saveConfig 里的 commit 一并落盘
  }
  deviceId = id;
  deviceKey = key;

  saveConfig(ssid, password);                 // 写魔数+SSID+PASS，并 commit(含上面的 DEVID/KEY)
  Serial.printf("[Config] saved ssid=%s, deviceId=%s, rebooting\n", ssid.c_str(), id.c_str());
  // 响应里带上 deviceId 和 key——手机端据此保存设备并完成"配对"，此后命令都要带 key
  server.send(200, "application/json",
              "{\"status\":\"ok\",\"deviceId\":\"" + id + "\",\"key\":\"" + key + "\"}");
  delay(800);                                 // 等响应发完再重启（否则 TCP 可能没 flush 就复位）
  ESP.restart();
}

// 恢复出厂接口：任意模式访问 /reset 都会清配置并重启回 AP 配网状态（无需重刷固件）
void handleReset() {
  server.send(200, "application/json", "{\"status\":\"reset\",\"rebooting\":true}");
  delay(400);
  clearConfig();
  ESP.restart();
}

// 状态接口：返回设备身份、固件版本、模式、IP、信号、MQTT 连接、是否安全模式、四路引脚电平。
// 手机端用它判断设备在线/离线，也用来确认 secure 字段（决定是否提示用户"重配网注册密钥"）。
void handleStatus() {
  String json = "{\"device\":\"" + deviceId + "\"";
  json += ",\"fw\":\"" + String(FW_VERSION) + "\"";
  json += ",\"mode\":\"" + String(WiFi.getMode() == WIFI_AP ? "ap" : "sta") + "\"";
  json += ",\"ip\":\"" + WiFi.localIP().toString() + "\"";
  json += ",\"rssi\":" + String(WiFi.RSSI());                                 // 信号强度(dBm)，越大越好
  json += ",\"mqtt\":" + String(mqtt.connected() ? "true" : "false");
  json += ",\"secure\":" + String(deviceKey.length() > 0 ? "true" : "false");// 是否已注册密钥并强制校验
  json += ",\"pins\":[";                                                // 四路实时电平，调试很方便
  for (int i = 0; i < 4; i++) {
    if (i) json += ",";
    json += "{\"pin\":" + String(GPIOS[i]) + ",\"level\":" + String(digitalRead(GPIOS[i])) + "}";
  }
  json += "]}";
  server.send(200, "application/json", json);
}

// 根路径：极简说明页，方便浏览器直接访问确认设备在线
void handleRoot() {
  server.send(200, "text/html",
    "<h3>ESP8266 IO Controller " FW_VERSION "</h3>"
    "<p>POST /config?ssid=..&amp;password=.. —— 下发 WiFi 配置</p>"
    "<p>GET&nbsp; /status —— 查看运行状态</p>"
    "<p>GET&nbsp; /reset —— 清除 WiFi 配置并重启（重新进入配网热点）</p>");
}

// 注册所有 HTTP 路由。/config 同时支持 POST(实际配网) 与 GET(返回 deviceId 供 App 预读)。
// server.begin() 启动监听；onNotFound 兜底到根页面。
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

// 进入配网热点模式：开 AP(名=ESP_+MAC6, 密码 espconfig)，IP 固定 192.168.4.1，
// 然后挂上全部 HTTP 路由，等手机来配网。
void startAP() {
  WiFi.mode(WIFI_AP);
  deviceId = AP_PREFIX + getMAC6();           // AP 模式用 MAC 派生名（仅发现用，非安全身份）
  WiFi.softAP(deviceId.c_str(), AP_PASSWORD);
  Serial.printf("[AP] started SSID=%s  pwd=%s  IP=192.168.4.1\n",
                deviceId.c_str(), AP_PASSWORD);
  setupRoutes();
}

// 进入 Station(联网)模式：读 WiFi 配置→连网→连 MQTT→订阅→开放 HTTP 控制台。
void startStation() {
  String ssid = readEEPROM(SSID_ADDR, SSID_MAX);
  String password = readEEPROM(PASS_ADDR, PASS_MAX);

  // 载入配对身份：已注册随机 deviceId 则覆盖 setup 里默认的 ESP_xxxxxx；
  // 否则保持 MAC 派生 ID 并进入"遗留兼容模式"（deviceKey 为空，命令不校验）。
  String storedId = readEEPROM(DEVID_ADDR, DEVID_MAX);
  if (storedId.length() > 0) deviceId = storedId;
  deviceKey = readEEPROM(KEY_ADDR, KEY_MAX);

  if (!connectToWiFi(ssid, password)) {
    Serial.println("[Boot] WiFi failed -> fall back to AP mode");
    startAP();                                // 连不上家WiFi(密码错/信号差)→回退热点，不卡死
    return;
  }
  // 配置 MQTT（服务器/回调/keepAlive/缓冲区），再重连并订阅；最后开放 HTTP 控制台
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
// 上电只跑一次：初始化串口/EEPROM/GPIO，处理恢复出厂，再根据有无配置决定进 AP 还是 STA。
void setup() {
  Serial.begin(115200);                 // 调试串口，波特率 115200（注意 bootloader 是 74880，别混）
  EEPROM.begin(EEPROM_SIZE);            // 必须 begin 才能用 EEPROM 读写

  // 四路输出默认设输出模式并拉低，避免上电瞬间误触发继电器/电磁阀
  for (int i = 0; i < 4; i++) {
    pinMode(GPIOS[i], OUTPUT);
    digitalWrite(GPIOS[i], LOW);
  }

  deviceId = AP_PREFIX + getMAC6();     // 先给个临时 AP 名（真正的随机 ID 在配网/载入时才确定）
  Serial.printf("\n=== ESP8266 4-IO Pulse Controller %s ===\ndeviceId = %s\n",
                FW_VERSION, deviceId.c_str());

  // 恢复出厂：上电时按住 FLASH(GPIO0) 键 2 秒（给 2s 容差，防手抖误触发）
  pinMode(RESET_PIN, INPUT_PULLUP);     // GPIO0 内部上拉，按键接地时读 LOW
  if (digitalRead(RESET_PIN) == LOW) {
    Serial.println("[Boot] reset key held, keep pressing 2s to clear config...");
    unsigned long t0 = millis();
    while (digitalRead(RESET_PIN) == LOW && millis() - t0 < RESET_HOLD_MS) delay(100);
    if (digitalRead(RESET_PIN) == LOW) {
      clearConfig();                    // 确认仍按住 → 清配置
      Serial.println("[Boot] factory reset done, rebooting");
      delay(300);
      ESP.restart();
    }
    Serial.println("[Boot] released too early, normal boot");  // 提前松手 → 当没按过，正常启动
  }

  // 没有有效 WiFi 配置（全新/被清）→ 起热点等配网；否则进入联网模式
  if (!hasValidConfig()) {
    Serial.println("[Boot] no valid WiFi config -> AP mode");
    startAP();
    return;                             // AP 模式下 loop 只处理 HTTP，不连 MQTT
  }

  startStation();
}

/* ====================== Loop ====================== */
// 主循环：每圈做的事情——① 收尾脉冲 ② 处理 HTTP ③ 保活 WiFi ④ 保活 MQTT ⑤ 心跳 ⑥ 让出 CPU。
// 全程不调用长 delay，保证 MQTT 消息和脉冲都能被及时响应。
void loop() {
  servicePulse();                       // 先处理脉冲结束，任何情况下都不拖延（最高优先级）
  server.handleClient();                // AP / STA 两种模式都监听 HTTP（配网/状态/重置）

  if (WiFi.getMode() == WIFI_AP) return; // AP 模式到此为止，不碰 WiFi/MQTT 重连逻辑

  // WiFi 断线自愈：掉网后重新 connectToWiFi；连上后再把 MQTT 参数配置好（重连逻辑在下面）
  if (WiFi.status() != WL_CONNECTED) {
    String ssid = readEEPROM(SSID_ADDR, SSID_MAX);
    String password = readEEPROM(PASS_ADDR, PASS_MAX);
    if (!connectToWiFi(ssid, password)) {
      startAP();                        // 重连也失败（比如家里路由器关了）→ 退回热点，可被重新配网
      return;
    }
    mqtt.setServer(MQTT_SERVER, MQTT_PORT);
    mqtt.setCallback(mqttCallback);
    mqtt.setKeepAlive(MQTT_KEEPALIVE);
    mqtt.setBufferSize(MQTT_BUFFER);
  }

  // MQTT 断线重连：最多每 5s 试一次（避免狂重连打爆 broker 或被限流）
  if (!mqtt.connected()) {
    unsigned long now = millis();
    if (now - lastReconnectAttempt > 5000) {
      lastReconnectAttempt = now;
      mqttReconnect();
    }
  }
  mqtt.loop();                          // 必须常调：驱动 PubSubClient 收发与保活

  // 心跳：每 HEARTBEAT_MS 发一次 online(retain)，证明在线；App 据此刷新在线状态
  unsigned long now = millis();
  if (now - lastHeartbeat > HEARTBEAT_MS) {
    lastHeartbeat = now;
    publishState(0, "online", true);
  }

  yield();                              // 让出 CPU 给系统任务(WiFi 协议栈等)，防止看门狗复位
}
