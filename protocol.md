# Minecraft ↔ AstrBot WebSocket 通讯协议

> 中立协议文档,供 ZenithProxy(游戏端)与 AstrBot(中枢端)两端共同遵守,用于两侧对接沟通。
> 配合文档:`minecraft_design.md`(ZenithProxy 端设计)、`astrbot_design.md`(AstrBot 端设计)。

## 1. 角色与连接

- **游戏端 (G)**:ZenithProxy + Java 插件,WebSocket **客户端**
- **中枢端 (S)**:AstrBot + Python 插件,WebSocket **服务端**
- G 主动连接 `ws://{ASTRBOT_IP}:8765/ws`,断线自动重连(间隔 5s)
- 鉴权:WS 握手请求头 `Authorization: Bearer {SHARED_TOKEN}`,两端一致;鉴权失败服务端返回 `401` 并断开

## 2. 帧格式

全部为 **JSON 文本帧**,用 `type` 字段区分方向与类别,必须包含 `type`。

## 3. 消息总表

| type | 方向 | 用途 |
|---|---|---|
| `hello` / `hello_ack` | G→S / S→G | 握手 |
| `heartbeat` / `heartbeat_ack` | G→S / S→G | 保活 |
| `update_info` / `update_info_ack` | G→S / S→G | 运行时更新注册信息 |
| `event` | G→S | 游戏事件上报 |
| `task` / `task_result` | S→G / G→S | 操作指令 / 执行结果 |
| `query` / `query_result` | S→G / G→S | 数据请求 / 查询结果 |

## 4. 握手

连接成功后 G 发送 `hello`(推荐):

```json
{
  "type": "hello",
  "server_id": "main",
  "server_name": "My Minecraft Server",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

S 回复 `hello_ack`:

```json
{
  "type": "hello_ack",
  "server_id": "main",
  "capabilities": ["chat", "query", "task"],
  "timestamp": 1693123456
}
```

> **兼容方式**:若 G 以 `heartbeat` 作为首条消息,S 也接受并回复 `heartbeat_ack`,进入兼容模式。

## 5. 心跳

- G 每 `heartbeat_interval`(默认 5s)发送一次 `heartbeat`;S 对每条 `heartbeat` 回复一条 `heartbeat_ack`
- S 以收到任意消息的时间作为存活依据,超过 `heartbeat_timeout`(建议 3×interval,默认 15s)判定下线
- **G 端存活检测**:连续 `3×heartbeat_interval` 未收到 `heartbeat_ack`,判定连接死亡,强制重连

```json
{"type": "heartbeat", "server_id": "main", "timestamp": 1693123456}
```

## 6. 事件上报(G→S)

统一包装:

```json
{
  "type": "event",
  "server_id": "main",
  "event_type": "chat",
  "data": {"sender": "xxx", "message": "yyy"},
  "timestamp": 1693123456
}
```

### event_type 总表

| event_type | data 字段 | 说明 |
|---|---|---|
| `chat` | `sender`(str), `message`(str) | 玩家公共聊天 |
| `whisper` | `outgoing`(bool), `sender`(str), `receiver`(str), `message`(str) | 私聊 |
| `system` | `message`(str) | 服务器系统消息 |
| `player_join` | `player`(str) | 玩家加入游戏 |
| `player_leave` | `player`(str) | 玩家离开游戏 |
| `bot_status` | `status`(str), `bot_name`(str), `health`(float), `food`(int), `position`{x,y,z} | Bot 状态,`bot_name` 为当前登录 BOT ID |
| `death` | - | Bot 死亡 |
| `achievement` | `player`(str), `achievement`(str) | 玩家达成成就 |

字段说明:
- `status` 取值:`online` / `offline` / `dead`
- `achievement` 仅在游戏端可解析成就公告文本时上报(如 `xxx has made the advancement: yyy`)

## 7. 更新信息(G→S)

连接后 G 可随时更新注册信息,无需断开重连:

```json
{
  "type": "update_info",
  "server_id": "AMineCat",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

S 更新注册表后回复:

```json
{
  "type": "update_info_ack",
  "server_id": "AMineCat",
  "timestamp": 1693123456
}
```

> S 收到 `update_info` 后重新注册连接:旧 `server_id` 移除,新 `server_id` 加入注册表。

## 8. 任务(S→G)

```json
{
  "type": "task",
  "task_id": "unique_001",
  "action": "send_chat",
  "params": {"message": "大家好！"}
}
```

### 已约定 action

| action | params | 说明 |
|---|---|---|
| `send_chat` | `message`(str) | Bot 发送公共聊天 |
| `move_to` | `x`(int), `y`(int), `z`(int) | Baritone 寻路移动 |
| `attack_nearest` | `radius`(float, 可选, 默认16) | 攻击最近的非玩家攻击性实体 |

执行完成后 G 回复:

```json
{
  "type": "task_result",
  "task_id": "unique_001",
  "success": true,
  "error_message": null
}
```

## 9. 查询(S→G)

```json
{
  "type": "query",
  "query_id": "query_001",
  "resource": "online_players",
  "params": {}
}
```

### 已约定 resource 与返回 data

| resource | params | data 返回结构 |
|---|---|---|
| `online_players` | - | `{"players":[{"name","ping"}],"count":n}` |
| `bot_status` | - | `{"status","health","food","position":{"x","y","z"},"heldItemSlot"}` |
| `inventory` | - | `{"items":[{"slot","id","name","count"}],"count":n}` |
| `nearby_entities` | `radius`(float, 默认32) | `{"entities":[{"entity_id","type","player"?,"distance","x","y","z"}],"count":n}` |

G 回复:

```json
{
  "type": "query_result",
  "query_id": "query_001",
  "success": true,
  "data": {},
  "error_message": null
}
```

## 10. 请求-响应关联

- `task_id` / `query_id` 由 S 生成(UUID),响应原样带回
- S 维护 pending 队列,收到结果后按 id 立即解析,超时默认 10s(`rpc_timeout`)
- 超时未响应视为失败,由 S 处理补偿

## 11. 多实例

同一 AstrBot 可接入多个 Minecraft 服务端:以 `server_id` 区分。S 连接注册表 `dict[server_id -> BridgeConnection]`。

## 12. 事件 → QQ 群推送(AstrBot 侧行为)

AstrBot 将接收到的 `event` 按以下规则推送到 QQ 群(目标群号配置为 `minecraft_event_group`,内部转换为 `aiocqhttp:group:{group_id}`):

| 事件类型 | 触发开关 | QQ 群消息格式 |
|---|---|---|
| `chat` | `chat_push_enabled` | `[server_id] 玩家名: 消息内容` |
| `whisper` | `chat_push_enabled` | `[server_id] 玩家名 -> 目标: 消息内容` |
| `player_join` | `server_event_push_enabled` | `[server_id] 玩家 玩家名 加入了游戏` |
| `player_leave` | `server_event_push_enabled` | `[server_id] 玩家 玩家名 离开了游戏` |
| `death` | `server_event_push_enabled` | `[server_id] Bot 死亡了` |
| `achievement` | `server_event_push_enabled` | `[server_id] 玩家 玩家名 达成了成就: 成就名` |
| `system` | `server_event_push_enabled` | `[server_id] 系统: 消息内容` |
| `bot_status` | 可选(未默认推送) | 自定 |

## 13. 两端配置项

| 配置项 | 默认值 | 描述 |
|---|---|---|
| `ws_host` | `0.0.0.0` | S 端 WS 监听地址 |
| `ws_port` | `8765` | S 端 WS 监听端口 |
| `ws_path` | `/ws` | S 端 WS 路径 |
| `shared_token` | `change-me` | 共享鉴权 Token(必须修改) |
| `default_server_id` | `default` | 默认服务器实例 ID |
| `heartbeat_interval` | `5` | 心跳间隔秒数(G 发 / S 可校验) |
| `heartbeat_timeout` | `15` | 心跳超时秒数(S 判定下线) |
| `rpc_timeout` | `10` | task/query 超时秒数 |

## 14. 安全

- Token 必须修改为唯一值
- 生产环境建议绑定内网或加 TLS(`wss://`),公网需放行防火墙/安全组
- 危险动作(如封禁、删除物品)两端均应拒绝

## 15. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-06 | v1.0 | 初稿:握手/心跳/事件/任务/查询 |
| 2026-09-07 | v1.1 | 新增 `update_info`;新增 `death`/`achievement` 事件;`bot_status` 增加 `bot_name`;事件→QQ 推送规则 |