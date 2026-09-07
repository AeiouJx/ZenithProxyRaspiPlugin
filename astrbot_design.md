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
| `heartbeat` / `heartbeat_ack` | G→S / S→G | 保活 |
| `event` | G→S | 游戏事件上报 |
| `task` | S→G | 下发操作指令 |
| `query` | S→G | 请求实时数据 |
| `task_result` | G→S | 任务执行结果 |
| `query_result` | G→S | 查询结果 |
| `update_info` / `update_info_ack` | G→S / S→G | 实时更新客户端信息（如 server_id） |

### 3.4 客户端 → 服务端（游戏 → AstrBot）

**heartbeat**
```json
{"type": "heartbeat", "server_id": "main", "timestamp": 1693123456}
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
| `bot_status` | `status`(str), `bot_name`(str), `health`(float), `food`(int), `position`{x,y,z} | Bot 状态（bot_name 为当前登录的 BOT ID） |
| `death` | - | Bot 死亡 |
| `achievement` | `player`(str), `achievement`(str) | 玩家达成公开成就 |

**update_info**（实时更新客户端信息）

客户端可以在连接后随时发送 `update_info` 更新 server_id 等信息，无需断开重连：

```json
{
  "type": "update_info",
  "server_id": "AMineCat",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

服务端回复 `update_info_ack`：

```json
{
  "type": "update_info_ack",
  "server_id": "AMineCat",
  "timestamp": 1693123456
}
```

> **注意**：`update_info` 会触发服务端重新注册连接，旧的 server_id 会被移除，新的 server_id 会加入注册表。

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
| `chat_push_enabled` | `false` | 将 Minecraft 玩家聊天推送到 QQ 群 |
| `server_event_push_enabled` | `false` | 将玩家上下线、死亡、成就等事件推送到 QQ 群 |
| `minecraft_event_group` | `""` | 事件推送目标 QQ 群号 |

### 4.2 事件 → QQ 群映射

Minecraft 事件通过 AstrBot 的 `context.send_message()` 推送到配置的 QQ 群。

**目标群确定方式**：
- 固定群号：配置 `minecraft_event_group` 为 QQ 群号（如 `860647561`）
- 内部转换为 UMOP 格式：`aiocqhttp:group:{group_id}`

**消息格式**：

| 事件类型 | QQ 群消息格式 |
|---|---|
| `chat` | `[server_id] 玩家名: 消息内容` |
| `whisper` | `[server_id] 玩家名 -> 目标: 消息内容` |
| `player_join` | `[server_id] 玩家 玩家名 加入了游戏` |
| `player_leave` | `[server_id] 玩家 玩家名 离开了游戏` |
| `death` | `[server_id] Bot 死亡了` |
| `achievement` | `[server_id] 玩家 玩家名 达成了成就: 成就名` |
| `system` | `[server_id] 系统: 消息内容` |

**注意**：
- `chat` 事件需要 `chat_push_enabled=true`
- 其他事件需要 `server_event_push_enabled=true`
- `whisper` 事件目前推送到群，未来可能支持推送到私聊

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
