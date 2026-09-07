# ZenithProxy + AstrBot WebSocket + AI 集成协议文档

## 1. 项目愿景

构建 **AstrBot 插件**（WebSocket 服务端）与 **ZenithProxy 插件**（WebSocket 客户端），通过 WebSocket 全双工通信，将 Minecraft 游戏事件与 QQ 群聊深度整合，并开放 AI 工具调用能力，实现对游戏 Bot 的监控、查询与控制。

- **AstrBot 仓库**：https://github.com/AeiouJx/astrbot_plugin_minecraft_bridge
- **ZenithProxy 仓库**：https://github.com/AeiouJx/ZenithProxyRaspiPlugin

## 2. 核心架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    游戏服务端 (ZenithProxy + Java 插件)           │
│  WebSocket 客户端 │ 事件上报 / 任务执行 / 查询处理               │
└───────────────────────────┬─────────────────────────────────────┘
                            │ ws://ASTRBOT_IP:8765/ws
                            │ Authorization: Bearer TOKEN
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    中枢服务 (AstrBot + Python 插件)              │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐   │
│  │ WS 服务端    │  │ 平台适配器    │  │ AI 工具 (8个)       │   │
│  │ 0.0.0.0:8765│  │ minecraft_   │  │ @filter.llm_tool    │   │
│  │ /ws         │  │ bridge       │  │                     │   │
│  └─────────────┘  └──────────────┘  └─────────────────────┘   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    交互层                                        │
│  QQ 群聊 · WebUI 面板 · 命令控制                                 │
└─────────────────────────────────────────────────────────────────┘
```

- **Minecraft 为源**：ZenithProxy 是执行器（负责采事件、执行任务、应答查询）
- **AstrBot 为中心**：负责消息路由、会话、AI 编排
- **全双工持久连接**：客户端（游戏端）主动连接服务端（AstrBot），握手一次，双向收发

## 3. 通信协议规范

### 3.1 连接与鉴权

- **服务端**：AstrBot 插件监听 `0.0.0.0:8765`，路径 `/ws`
- **客户端**：游戏端插件启动时主动连接 `ws://{ASTRBOT_IP}:8765/ws`，断线自动重连（间隔 5s）
- **鉴权**：WS 握手请求头 `Authorization: Bearer {SHARED_TOKEN}`，两端需一致；服务端鉴权失败返回 `401` 并断开

### 3.2 握手流程

**推荐方式（hello 握手）**：

客户端连接后发送 `hello` 消息：
```json
{
  "type": "hello",
  "server_id": "main",
  "server_name": "My Minecraft Server",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

服务端回复 `hello_ack`：
```json
{
  "type": "hello_ack",
  "server_id": "main",
  "capabilities": ["chat", "query", "task"],
  "timestamp": 1693123456
}
```

**兼容方式（heartbeat 握手）**：

如果客户端发送 `heartbeat` 作为首条消息，服务端也会接受并回复 `heartbeat_ack`，进入兼容模式。

### 3.3 消息总表

全部为 JSON 文本帧，`type` 字段区分方向与类别。

| type | 方向 | 用途 |
|---|---|---|
| `hello` / `hello_ack` | G→S / S→G | 握手（推荐） |
| `update_info` / `update_info_ack` | G→S / S→G | 运行时更新注册信息（server_id 等，无需重连） |
| `heartbeat` / `heartbeat_ack` | G→S / S→G | 保活 |
| `event` | G→S | 游戏事件上报 |
| `task` | S→G | 下发操作指令 |
| `query` | S→G | 请求实时数据 |
| `task_result` | G→S | 任务执行结果 |
| `query_result` | G→S | 查询结果 |

### 3.4 客户端 → 服务端（游戏 → AstrBot）

**heartbeat**
```json
{"type": "heartbeat", "server_id": "main", "timestamp": 1693123456}
```

**update_info**（运行时更新注册信息，客户端无需重连）
```json
{
  "type": "update_info",
  "server_id": "AMineCat",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

服务端回复 `update_info_ack`（更新注册表后回）：
```json
{
  "type": "update_info_ack",
  "server_id": "AMineCat",
  "timestamp": 1693123456
}
```

**event**（统一包装）
```json
{
  "type": "event",
  "server_id": "main",
  "event_type": "chat",
  "data": {...},
  "timestamp": 1693123456
}
```

`event_type` 类型：

| event_type | data 字段 | 说明 |
|---|---|---|
| `chat` | `sender`(str), `message`(str) | 玩家公共聊天 |
| `whisper` | `outgoing`(bool), `sender`(str), `receiver`(str), `message`(str) | 私聊 |
| `system` | `message`(str) | 服务器系统消息 |
| `player_join` | `player`(str) | 玩家加入 |
| `player_leave` | `player`(str) | 玩家离开 |
| `bot_status` | `status`(str), `health`(float), `food`(int), `position`{x,y,z} | Bot 状态 |
| `death` | - | Bot 死亡 |

**task_result**
```json
{
  "type": "task_result",
  "task_id": "unique_001",
  "success": true,
  "error_message": null
}
```

**query_result**
```json
{
  "type": "query_result",
  "query_id": "query_001",
  "success": true,
  "data": {...},
  "error_message": null
}
```

### 3.5 服务端 → 客户端（AstrBot → 游戏）

**task**
```json
{
  "type": "task",
  "task_id": "unique_001",
  "action": "send_chat",
  "params": {"message": "大家好！"}
}
```

已实现 action：

| action | params | 说明 |
|---|---|---|
| `send_chat` | `message`(str) | Bot 发送公共聊天 |
| `move_to` | `x`,`y`,`z`(int) | Baritone 寻路移动 |
| `attack_nearest` | `radius`(float, 可选, 默认16) | 攻击最近的非玩家攻击性实体 |

**query**
```json
{
  "type": "query",
  "query_id": "query_001",
  "resource": "online_players",
  "params": {}
}
```

已实现 resource：

| resource | params | data 返回结构 |
|---|---|---|
| `online_players` | - | `{"players":[{"name","ping"}],"count":n}` |
| `bot_status` | - | `{"status","health","food","position":{"x","y","z"},"heldItemSlot"}` |
| `inventory` | - | `{"items":[{"slot","id","name","count"}],"count":n}` |
| `nearby_entities` | `radius`(float,默认32) | `{"entities":[{"entity_id","type","player"?,"distance","x","y","z"}],"count":n}` |

### 3.6 请求-响应关联

`task_id` / `query_id` 由服务端生成（UUID），响应原样带回。服务端维护 pending 队列，收到结果后按 id 立刻解析，超时默认 10s。

### 3.7 多实例

同一 AstrBot 可接多个 Minecraft 服务端：以 `server_id` 区分客户端。服务端连接注册表 `dict[server_id -> BridgeConnection]`。

## 4. 配置项

| 配置项 | 默认值 | 描述 |
|---|---|---|
| `ws_host` | `0.0.0.0` | WS 监听地址 |
| `ws_port` | `8765` | WS 监听端口 |
| `ws_path` | `/ws` | WS 路径 |
| `shared_token` | `change-me` | 共享鉴权 Token（必须修改） |
| `default_server_id` | `default` | 默认服务器实例 ID |
| `heartbeat_timeout` | `15` | 心跳超时秒数 |
| `rpc_timeout` | `10` | RPC 超时秒数 |
| `group_id_prefix` | `minecraft` | 虚拟群 ID 前缀 |
| `bridge_on` | `false` | 插件加载时自动启动 WS 服务 |

## 5. AI 工具清单

| 工具名 | 类型 | 说明 |
|---|---|---|
| `get_server_status` | query | 获取服务器连接状态 |
| `get_online_players` | query | 获取在线玩家列表 |
| `get_bot_state` | query | 获取 Bot 详细状态 |
| `get_inventory` | query | 获取背包物品列表 |
| `get_nearby_entities` | query | 获取附近实体 |
| `move_bot` | task | 让 Bot 移动到指定坐标 |
| `attack_nearest` | task | 让 Bot 攻击最近的攻击性实体 |
| `send_chat` | task | 让 Bot 发送聊天消息 |

## 6. Web 面板

AstrBot 插件提供 Web 面板，可在插件详情页的 "Pages" 标签页访问：

- **Bridge Status**：显示 WS 服务状态、监听地址、在线服务器数
- **Connected Servers**：已连接服务器列表（server_id, mod_version, capabilities）
- **Configuration**：可编辑所有配置项

API 端点：
- `GET /astrbot_plugin_minecraft_bridge/status` - 获取状态
- `GET /astrbot_plugin_minecraft_bridge/servers` - 获取服务器列表
- `POST /astrbot_plugin_minecraft_bridge/start` - 启动 WS 服务
- `POST /astrbot_plugin_minecraft_bridge/stop` - 停止 WS 服务
- `GET /astrbot_plugin_minecraft_bridge/config` - 获取配置
- `POST /astrbot_plugin_minecraft_bridge/config/save` - 保存配置

## 7. 安全注意事项

- **Token 必改**：默认 `change-me`，请配置唯一值
- **网络隔离**：`ws_port` 建议仅绑内网，公网需放防火墙/TLS
- **最小权限**：Java 端仅实现 `send_chat/move_to/attack_nearest`，危险动作拒绝

## 8. ZenithProxy Java 端对接清单

Java 端需要实现：

1. **连接**：WebSocket 客户端连接 `ws://{ASTRBOT_IP}:8765/ws`
2. **鉴权**：请求头 `Authorization: Bearer {TOKEN}`
3. **握手**：连接后发送 `hello` 消息（推荐）或 `heartbeat`（兼容）
4. **心跳**：定期发送 `heartbeat` 保活
5. **事件上报**：chat/whisper/system/player_join/player_leave/bot_status/death
6. **任务处理**：接收 `task`，执行后返回 `task_result`
7. **查询处理**：接收 `query`，执行后返回 `query_result`

### hello 消息示例

```json
{
  "type": "hello",
  "server_id": "main",
  "server_name": "My Server",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

### heartbeat 消息示例

```json
{
  "type": "heartbeat",
  "server_id": "main",
  "timestamp": 1693123456
}
```

## 8. 事件推送实现检查清单（ZenithProxy Java 端）

**这是 AstrBot Dashboard 实时显示的前提条件。** ZenithProxy 必须主动把 Minecraft 游戏事件通过 WS 推送到 AstrBot。

### 8.1 必须实现的事件推送

| 事件 | event_type | data 字段 | 触发时机 |
|---|---|---|---|
| 公共聊天 | `chat` | `sender`(string), `message`(string) | 任何玩家在公共频道发言 |
| 私聊 | `whisper` | `outgoing`(bool), `sender`(string), `receiver`(string), `message`(string) | 私聊消息 |
| 玩家加入 | `player_join` | `player`(string) | 玩家加入服务器 |
| 玩家离开 | `player_leave` | `player`(string) | 玩家离开服务器 |
| 系统消息 | `system` | `message`(string) | 服务器系统消息（如成就、命令反馈） |
| Bot 死亡 | `death` | `death_message`(string, 可选) | Bot 实体死亡 |
| 成就 | `achievement` | `player`(string), `achievement`(string) | 玩家获得成就 |

### 8.2 消息格式

所有事件必须用以下格式通过 WS 发送 JSON 文本帧：

```json
{
  "type": "event",
  "event_type": "chat",
  "data": {
    "sender": "huayan666",
    "message": "你好"
  },
  "timestamp": 1693123456
}
```

**关键要求**：
- `type` 必须是 `"event"`（不是 `"chat"` 或其他）
- `event_type` 必须是上述表格中的值
- `data` 必须是对象，包含对应的字段
- `timestamp` 建议带上（秒级 Unix 时间戳）

### 8.3 常见问题排查

**Q: AstrBot Dashboard 显示 "Connected Servers: 0"**
- 检查 ZenithProxy 是否发送了 `hello` 握手消息
- 检查 `Authorization: Bearer {TOKEN}` 头是否正确

**Q: Dashboard 显示 Connected Servers 但消息区域空白**
- ZenithProxy 可能没有实现事件推送（只实现了 hello/heartbeat/task/query）
- 检查 ZenithProxy 是否监听了 Minecraft 的 `ChatReceivedEvent`、`PlayerJoinEvent`、`PlayerLeaveEvent` 等事件
- 检查推送的消息格式是否符合 8.2 节规范

**Q: AstrBot 日志中看到 "handle_data_message" 但没有 "chat"**
- 事件到达了但 event_type 不对，检查 ZenithProxy 发送的 event_type 值

**Q: AstrBot 日志中没有任何事件日志**
- ZenithProxy 没有发送事件，检查 Java 端事件监听代码

### 8.4 ZenithProxy Java 端实现参考

在 ZenithProxy Java 插件（BridgeModule）中，**事件上报已实现**，事件监听如下（注意：ZenithProxy 不是 Bukkit/Forge，事件类不同）：

1. **监听 ZenithProxy 事件**：
   - `PublicChatEvent` → 推送 `chat` 事件
   - `WhisperChatEvent` → 推送 `whisper` 事件
   - `SystemChatEvent` → 推送 `system` 事件；成就公告文本按 §3.4 规则解析为 `achievement` 事件
   - `ServerPlayerConnectedEvent` → 推送 `player_join` 事件
   - `ServerPlayerDisconnectedEvent` → 推送 `player_leave` 事件
   - `ClientOnlineEvent` / `ClientDisconnectEvent` / `ClientDeathEvent` → 推送 `bot_status` 事件（`data.bot_name` 为当前登录 BOT ID）；死亡额外推送 `death` 事件

2. **通过 WS 发送**：
   ```java
   // 伪代码
   JSONObject event = new JSONObject();
   event.put("type", "event");
   event.put("event_type", "chat");
   event.put("data", new JSONObject()
       .put("sender", playerName)
       .put("message", chatMessage));
   event.put("timestamp", System.currentTimeMillis() / 1000);
   wsClient.send(event.toString());
   ```

3. **确保 WebSocket 连接存活**：事件推送依赖 WS 连接，如果连接断开事件会丢失
