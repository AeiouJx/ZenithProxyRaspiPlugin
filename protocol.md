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
  "server_id": "raspberrypi-b7e42d91",
  "account": "Pearl",
  "hwid": "dc:a6:32:1e:4f:9a",
  "server_name": "My Minecraft Server",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

> `server_id` 为**稳定的实例 ID**,同一 ZenProxy 实例重启/换账号不变,用于多实例唯一区分(默认由插件自动生成 `设备名-随机8位`,如 `raspberrypi-b7e42d91`;可用 `/bridge serverId` 手动覆盖)。`account` 为当前登录的 MC 游戏账号名(可选,展示用;缺省回退 `server_id`)。`hwid` 为客户端**主网卡 MAC 地址**(可选,防盗防伪用,标识真实物理机器,不随配置/账号/重装变化)。

S 回复 `hello_ack`:

```json
{
  "type": "hello_ack",
  "server_id": "raspberrypi-b7e42d91",
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

> 分类用于语义归类与推送开关分组(见 §12):`CHAT` 聊天、`BOT` Bot 自身状态与行为、`THREAT` 针对 Bot 的威胁预警、`SERVER` 服务器内其他玩家/实体、`QUEUE` 排队、`CONN` 代理控制连接、`OPS` 运维(插件/更新/定时任务)、`SCAN` 扫描。
> **语义列描述触发时机与含义**,字段含义详见表后字段说明。

| event_type | 分类 | 语义(触发时机) | data 字段 |
|---|---|---|---|
| `chat` | CHAT | 玩家在**公共频道**发言(正版号走核心 PublicChatEvent;离线号 `||` 前缀玩家走 3c3u 公屏文本,由 G 端解析) | `sender`(str), `message`(str) |
| `whisper` | CHAT | Bot **收到或发出**私聊,`outgoing=false` 为他人发给 Bot,`outgoing=true` 为 Bot 发出(sender 为 `You`/Bot 名,receiver 为目标玩家) | `outgoing`(bool), `sender`(str), `receiver`(str), `message`(str) |
| `system` | CHAT | 服务器**无法归类的系统文本**(含 3c3u 专用格式兜底,G 端暂未识别的消息),按原文上报 | `message`(str) |
| `bot_status` | BOT | Bot **状态心跳**:上线/下线/死亡时上报,或定时状态(ID 展示用) | `status`(str), `bot_name`(str), `health`(float), `food`(int), `position`{x,y,z} |
| `death` | BOT | **Bot 本人死亡**(核心 ClientDeathEvent,生命归零),附死亡坐标 | `position`{x,y,z} |
| `bot_death_message` | BOT | **Bot 本人死亡**的服务器广播文本(核心 ClientDeathMessageEvent,如 `You were slain by xxx`) | `message`(str) |
| `disconnect` | BOT | **Bot 客户端断开连接**(含被踢/掉线/手动),附原因与在线时长 | `reason`(str), `manual`(bool), `online_duration_seconds`(int), `was_in_queue`(bool), `queue_position`(int) |
| `client_connecting` | BOT | **Bot 客户端开始连接**服务器 | - |
| `client_connected` | BOT | **Bot 客户端已连上**服务器 | - |
| `client_login_failed` | BOT | **Bot 登录失败**(账号/网络错误),`error` 为异常信息 | `error`(str) |
| `client_reconfiguring` | BOT | Bot 客户端进入**重配置阶段**(core 偶发) | - |
| `auto_reconnect` | BOT | **自动重连已调度**,`delay_seconds` 为倒计时秒数 | `delay_seconds`(int) |
| `auto_eat_out_of_food` | BOT | 自动进食模块 **无食物可吃** | - |
| `health_warning` | BOT | **Bot 血量**下降且低于 8 | `health`(float) |
| `health_autodisconnect` | BOT | **低血自动断线**已触发 | - |
| `prio_status` | BOT | 2b2t **优先队列状态变化**(获得/失去 prio) | `prio`(bool) |
| `msa_device_code` | BOT | **微软设备码登录**需要人工浏览器授权 | `url`(str), `code`(str) |
| `session_time_limit_warning` | BOT | **会话时长限制警告**:距被踢出剩余 n 分钟 | `duration_until_kick_minutes`(int), `session_time_limit_hours`(int) |
| `replay_started` | BOT | **录像开始** | - |
| `replay_stopped` | BOT | **录像结束**,可选带录像文件名 | `file`(str, 可选) |
| `active_hours_connect` | BOT | **活跃时段自动连接**触发,`will_wait=true` 表示先等待 1 分钟 | `will_wait`(bool) |
| `spawn_patrol_target` | BOT | **Bot 出生点巡逻模块发现目标玩家**及坐标(主动防御行为) | `player`(str), `x`(int), `y`(int), `z`(int) |
| `spawn_patrol_target_killed` | BOT | **出生点巡逻目标死亡**(Bot 击杀或自然死亡),`message` 为死亡文本 | `player`(str), `message`(str) |
| `attack` | THREAT | **有玩家攻击了 Bot**(Bot 被攻击预警),附攻击者名称与坐标 | `player`(str), `x`(num), `y`(num), `z`(num) |
| `totem_pop` | THREAT | **附近玩家 totem 被消耗**(可能正在与 Bot 对抗),剩余数量 | `totems_remaining`(int) |
| `totem_empty` | THREAT | **附近玩家已无 totem**(针对了我们或可能攻击我们,威胁解除迹象) | - |
| `player_join` | SERVER | **某玩家进入服务器**(非 Bot),`message` 为原生描述如 `k70pro joined the game` | `player`(str), `message`(str) |
| `player_leave` | SERVER | **某玩家离开服务器**(非 Bot),`message` 为原生描述如 `k70pro left the game` | `player`(str), `message`(str) |
| `achievement` | SERVER | **玩家达成成就**(核心 ChatParser 解析公告文本) | `player`(str), `achievement`(str) |
| `player_death` | SERVER | **服务器上玩家死亡广播**(victim 为死者,非 Bot;`cause` 为完整死亡原因原生文本如 `was slain by Eover`,killer/weapon 可选) | `victim`(str), `cause`(str), `killer`(str, 可选), `weapon`(str, 可选) |
| `visual_enter` | SERVER | **玩家进入 Bot 视野**(内存可见范围) | `player`(str), `x`(int), `y`(int), `z`(int) |
| `visual_leave` | SERVER | **玩家离开 Bot 视野** | `player`(str), `x`(int), `y`(int), `z`(int) |
| `visual_logout` | SERVER | **视野内玩家退出游戏**,坐标为最后下线位置 | `player`(str), `x`(int), `y`(int), `z`(int) |
| `connection_denied` | SERVER | **黑名单/非白名单玩家连接代理被拒**(代理安全),`reason` 取值 `blacklisted` / `not_whitelisted` | `player`(str), `ip`(str), `reason`(str) |
| `server_restarting` | SERVER | **服务器重启公告** | `message`(str) |
| `queue_position` | QUEUE | **排队位次更新**(节流:≤1 次/30s) | `position`(int) |
| `queue_complete` | QUEUE | **排队完成**,已进入服务器 | `duration_seconds`(int) |
| `queue_start` | QUEUE | **开始排队**,`was_online=true` 表示被踢回队列(如服务器重启) | `was_online`(bool), `online_duration_seconds`(int) |
| `queue_skip` | QUEUE | **排队被跳过**(直接进服) | - |
| `queue_warning` | QUEUE | **队位接近**(优先/博主提醒),`mention` 建议提醒 | `position`(int), `mention`(bool) |
| `client_player_connected` | CONN | **控制玩家通过 clientConnection 连接代理**(管理控制通道) | `player`(str), `mc_version`(str, 可选) |
| `client_player_disconnected` | CONN | **控制玩家断开代理** | `player`(str, 可选), `reason`(str, 可选) |
| `spectator_connected` | CONN | **观察者连接代理** | `player`(str), `mc_version`(str, 可选) |
| `spectator_disconnected` | CONN | **观察者断开代理** | `player`(str) |
| `plugin_load_failure` | OPS | **某个插件加载失败** | `id`(str, 可选), `jar`(str), `message`(str) |
| `plugin_loaded` | OPS | **某个插件加载成功** | `id`(str), `description`(str), `version`(str), `url`(str), `authors`(str) |
| `update_available` | OPS | **检测到有新版本**(重启后生效) | `version`(str, 可选) |
| `update_start` | OPS | **开始更新/重启** | `version`(str, 可选) |
| `tasks_command` | OPS | **内置定时任务执行了命令** | `command`(str) |
| `scan_found` | SCAN | **扫描首次发现目标**(方块/实体)及坐标 | `target`(str), `name`(str), `x`(int), `y`(int), `z`(int), `distance`(int) |

字段说明:
- `status` 取值:`online` / `offline` / `dead`
- `achievement` 仅在游戏端可解析成就公告文本时上报(如 `xxx has made the advancement: yyy`)
- `scan_found` 的 `target` 取值:`player` / `ender_pearl` / `item_frame` / `chest` / `ender_chest` / `shulker_box` / `soul_sand` / `bubble_column` / `sign` / `redstone`;`name` 为实际方块注册名(如 `minecraft:chest`)或实体显示名;`distance` 为到 Bot 的欧氏距离。扫描按**水平半径**在已加载区块全高度范围内查找(无垂直限制),仅对首见目标上报,目标消失后再出现会重新上报。

> **3c3u.org 服务器消息解析说明**:该服使用自定义聊天插件,所有聊天内容(公共聊天、私聊、系统消息)**统一走 `SystemChatEvent`**,`PublicChatEvent` / `WhisperChatEvent` 在本服不会触发。因此 **文本 → 结构化事件的解析全部由 G 端(插件)完成**:S 端收到的 `event_type` 已是归类结果(`chat`/`whisper`/`achievement`/`system` 等),S 端**只需按事件类型消费,无需也切勿自行解析原始文本**。
>
> G 端优先读取系统消息**原始组件的 `translate` 键**做强语义判断,失败再按文本正则兜底(以下为 G 端内部实现,仅供理解,不属于 S 端职责):
> 0. **translate 键预判** — 3c3u 的模板消息用 `translate` 字段标记类型:**以 `chat.type.advancement.` 开头 → 成就**;以 `death.` 开头、或值含 `%1$s`(内嵌模板,如 `%1$s was smashed by %2$s`)** → 玩家死亡**;普通聊天/私聊顶层无 translate。死亡时 `arguments()[0]` 取受害者、`arguments()[1]` 取击杀者(仅内嵌模板型)
> 1. **私聊** — 正则 `^📨\s*(.+?)\s*[➡→]\s*(.+)$` → `whisper`,输出 `{outgoing, sender, receiver, message}`:
>    - sender 为 `You`(或 == Bot 账号名)时 `outgoing=true`(Bot 借 3c3u 界面发出的私聊),此时**`receiver` = 箭头后首个空格词(目标玩家,含 `\|\|` 离线号原样)**,`message` = 剩余部分
>    - sender 为他人时 `outgoing=false`,`receiver` = 空(收件人恒为 Bot),`message` = 箭头后全部
> 2. **公共聊天** — 正则 `^«(.+?)»\s*(.*)$` → `chat`,输出 `{sender, message}`;`sender` 可含 `\|\|` 离线号前缀(原样保留),`message` 为 `»` 后全部内容(含 `>` 等原样保留)
> 3. **成就** — `xxx has made the advancement` / `xxx has completed the challenge` / `xxx has reached the goal`,成就名分隔符兼容 `: yyy` 与 `[yyy]` → `achievement`,输出 `{player, achievement}`
> 4. **玩家死亡** — `victim was/were/got/died ...` → `player_death`,输出 `{victim, cause, killer, weapon}`;死亡原因(`cause`)为动词短语原文(如 `slain by Eover`),`by <击杀者>` 提取 `killer`(`using <武器>` 提取 `weapon`),无 `by` 时 killer/weapon 为空串;`was/were/got/died` 前缀之外的死亡动词(如 `fell from a high place`)由第 0 步 translate 预判兜底
> 5. **兜底** — 其余全部 → `system`,输出原始文本 `{message}`

### 6.1 G 端 system 兜底消息再解析(持续扩展,无需 S 端配合)

实机测试中若发现某些消息在 3c3u 上**无法触发对应专用事件**(专用事件只在原版/标准消息格式下触发),它们会暂时以 `system` 原文本上报。G 端将在后续迭代中为这些格式**追加文本解析,逐步归类为结构化事件**;一旦新增/改变 `event_type`,本文档第 6 节总表与第 12 节推送表会同步更新。

当前已知可能走 `system` 兜底的消息类别(理论上可触发的事件/输出):

| 消息类别 | 理想事件 | 兜底原因 |
|---|---|---|
| Bot 自身死亡文本 | `bot_death_message` | `ClientDeathMessageEvent` 可能不触发,待 3c3u 死亡文本样例 |
| 玩家进服/出服提示 | `player_join` / `player_leave` | `ServerPlayerConnectedEvent`/`ServerPlayerDisconnectedEvent` 可能不触发 |
| 服务器重启公告 | `server_restarting` | `ServerRestartingEvent` 可能不触发 |
| 其他未识别系统提示 | - | 需测试后逐条归类 |

**协议约定**:S 端只需持续消费 `event_type`,无需因 G 端解析能力扩展而升级改版;对未识别的 `system` 消息按 §12 的 `system` 格式直接推送即可。

## 7. 更新信息(G→S)

连接后 G 可随时更新注册信息,无需断开重连:

```json
{
  "type": "update_info",
  "server_id": "raspberrypi-b7e42d91",
  "account": "Pearl",
  "hwid": "dc:a6:32:1e:4f:9a",
  "mod_version": "1.0.0",
  "capabilities": ["chat", "whisper", "query", "task"]
}
```

S 更新注册表后回复:

```json
{
  "type": "update_info_ack",
  "server_id": "raspberrypi-b7e42d91",
  "timestamp": 1693123456
}
```

> S 收到 `update_info` 后重新注册连接:旧 `server_id` 移除,新 `server_id` 加入注册表。
> `account` 为当前登录的 MC 游戏账号名(可选字段,用于 UI/QQ 推送展示;缺省时回退用 `server_id`)。`hwid` 为主网卡 MAC(可选,防盗防伪,跨 `server_id` 变化仍可识别同一机器)。

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

AstrBot 将接收到的 `event` 推送到 QQ 群(目标群号配置 `minecraft_event_group`,内部转换为 `aiocqhttp:group:{group_id}`)。**推送开关为三层结构**,与 §6 事件分类一一对应。

### 12.1 开关体系

- **总开关** `push_enabled`(默认 `true`):为 `false` 时一切事件不推送
- **分组开关**:按 §6 分类 `CHAT/BOT/THREAT/SERVER/QUEUE/CONN/OPS/SCAN` 细分(默认值见表)
- **事件级开关** `push_{event_type}`(默认 `跟随分组`):需要对单个事件独立控制时显式设置布尔值

判定顺序:`push_enabled` → 分组开关 → 事件级开关(**仅显式设置时覆盖分组**)。

| 分组开关 | 分组 | 默认 | 覆盖事件 |
|---|---|---|---|
| `push_chat_enabled` | CHAT | `true` | `chat` / `whisper` / `system` |
| `push_bot_enabled` | BOT | `true` | `bot_status` / `death` / `bot_death_message` / `disconnect` / `client_connecting` / `client_connected` / `client_login_failed` / `client_reconfiguring` / `auto_reconnect` / `auto_eat_out_of_food` / `health_warning` / `health_autodisconnect` / `prio_status` / `msa_device_code` / `session_time_limit_warning` / `replay_started` / `replay_stopped` / `active_hours_connect` / `spawn_patrol_target` / `spawn_patrol_target_killed` |
| `push_threat_enabled` | THREAT | `true` | `attack` / `totem_pop` / `totem_empty` |
| `push_server_enabled` | SERVER | `true` | `player_join` / `player_leave` / `achievement` / `player_death` / `visual_enter` / `visual_leave` / `visual_logout` / `connection_denied` / `server_restarting` |
| `push_queue_enabled` | QUEUE | `true` | `queue_position` / `queue_complete` / `queue_start` / `queue_skip` / `queue_warning` |
| `push_conn_enabled` | CONN | `false` | `client_player_connected` / `client_player_disconnected` / `spectator_connected` / `spectator_disconnected` |
| `push_ops_enabled` | OPS | `true` | `plugin_load_failure` / `plugin_loaded` / `update_available` / `update_start` / `tasks_command` |
| `push_scan_enabled` | SCAN | `false` | `scan_found` |

> 事件级开关默认值为 `跟随分组`;个别事件因语义(高频状态心跳、低敏感)建议默认不推,已在下方推送格式表标注 **默认不推**。

### 12.2 事件 → QQ 群消息格式

按 §6 分类分组;`event_type` 即 §6 总表中的事件类型,字段取值见 §6。

| event_type | 事件级开关(默认) | QQ 群消息格式 |
|---|---|---|
| **CHAT** | | |
| `chat` | 跟随分组 | `[server_id] {sender}: {message}` |
| `whisper` | 跟随分组 | `[server_id] {sender} -> {receiver}: {message}`(outgoing=true 时 `[server_id] Bot -> {receiver}: {message}`) |
| `system` | 跟随分组 | `[server_id] 系统: {message}` |
| **BOT** | | |
| `bot_status` | **默认不推** | 自定(状态心跳,建议仅在 debug) |
| `death` | 跟随分组 | `[server_id] 💀 Bot 死亡了 [x,y,z]` |
| `bot_death_message` | 跟随分组 | `[server_id] 💀 Bot 死亡: {message}` |
| `disconnect` | 跟随分组 | `[server_id] ⚠ Bot 断开: {reason} (在线 x s)`(manual=true 时为 `Bot 已手动断开`) |
| `client_connecting` | 跟随分组 | `[server_id] 🔌 正在连接服务器...` |
| `client_connected` | 跟随分组 | `[server_id] ✅ 已连接服务器` |
| `client_login_failed` | 跟随分组 | `[server_id] ❌ 登录失败: {error}` |
| `client_reconfiguring` | 跟随分组 | `[server_id] 🔧 客户端重新配置中` |
| `auto_reconnect` | 跟随分组 | `[server_id] 🔄 n 秒后自动重连` |
| `auto_eat_out_of_food` | 跟随分组 | `[server_id] ⚠ 自动进食无食物可用` |
| `health_warning` | 跟随分组 | `[server_id] ⚠ Bot 血量过低: n 点` |
| `health_autodisconnect` | 跟随分组 | `[server_id] ⚠ Bot 因低血自动断开连接` |
| `prio_status` | 跟随分组 | `[server_id] ⭐ 优先队列: 已获得/已失去`(prio=true/false) |
| `msa_device_code` | 跟随分组 | `[server_id] 🔑 微软登录: 打开 {url} 输入验证码 {code}` |
| `session_time_limit_warning` | 跟随分组 | `[server_id] ⏳ 会话时长限制: 剩余 n 分钟将被踢出` |
| `replay_started` | 跟随分组 | `[server_id] 🎥 录像开始` |
| `replay_stopped` | 跟随分组 | `[server_id] 🎥 录像结束`(有文件名时附 `: {file}`) |
| `active_hours_connect` | 跟随分组 | `[server_id] ⏰ 活跃时段连接触发`(will_wait=true 时附 `等待 1 分钟`) |
| `spawn_patrol_target` | 跟随分组 | `[server_id] 🎯 巡逻发现目标: {player} [x,y,z]` |
| `spawn_patrol_target_killed` | 跟随分组 | `[server_id] 💀 巡逻击杀目标: {player} ({message})` |
| **THREAT** | | |
| `attack` | 跟随分组 | `[server_id] ⚠ 玩家 {player} 攻击了 Bot [x,y,z]` |
| `totem_pop` | 跟随分组 | `[server_id] ⚠ 有人 totem 弹出, 剩余 n 个` |
| `totem_empty` | 跟随分组 | `[server_id] ⚠ 有人已无 totem, 警惕` |
| **SERVER** | | |
| `player_join` | 跟随分组 | `[server_id] {message}` |
| `player_leave` | 跟随分组 | `[server_id] {message}` |
| `achievement` | 跟随分组 | `[server_id] 🏆 玩家 {player} 达成了成就: {achievement}` |
| `player_death` | 跟随分组 | `[server_id] 💀 玩家 {victim} {cause}`(有击杀者/武器时附 ` ({killer} [{weapon}])`) |
| `visual_enter` | 跟随分组 | `[server_id] 👁 玩家 {player} 进入视野 [x,y,z]` |
| `visual_leave` | 跟随分组 | `[server_id] 👁 玩家 {player} 离开视野 [x,y,z]` |
| `visual_logout` | 跟随分组 | `[server_id] 👁 玩家 {player} 退出游戏 [x,y,z]` |
| `connection_denied` | 跟随分组 | `[server_id] 🚫 拒绝玩家 {player} 连接 (reason={reason})`(附 IP 可选) |
| `server_restarting` | 跟随分组 | `[server_id] ⚠ 服务器重启: {message}` |
| **QUEUE** | | |
| `queue_start` | 跟随分组 | `[server_id] ⚠ 开始排队 (被踢到队列)`(was_online=true 时带 `在线 x s 后被踢`) |
| `queue_skip` | 跟随分组 | `[server_id] 跳过排队, 直接进服` |
| `queue_warning` | 跟随分组 | `[server_id] ⚠ 队位警告: 当前第 n 位` |
| `queue_position` | 跟随分组 | `[server_id] 排队中, 当前位次: n` |
| `queue_complete` | 跟随分组 | `[server_id] 排队完成, 已进入服务器` |
| **CONN** | | |
| `client_player_connected` | 跟随分组(分组默认 `false`) | `[server_id] 🖥 控制玩家 {player} 已连接代理 (MC {mc_version})` |
| `client_player_disconnected` | 跟随分组(分组默认 `false`) | `[server_id] 🔌 控制玩家 {player} 断开代理`(带 reason 时附 `: {reason}`) |
| `spectator_connected` | 跟随分组(分组默认 `false`) | `[server_id] 👁 观察者 {player} 已连接代理 (MC {mc_version})` |
| `spectator_disconnected` | 跟随分组(分组默认 `false`) | `[server_id] 👁 观察者 {player} 已断开代理` |
| **OPS** | | |
| `plugin_load_failure` | 跟随分组 | `[server_id] ❌ 插件加载失败: {id} ({message})` |
| `plugin_loaded` | 跟随分组 | `[server_id] 📦 插件加载成功: {id} v{version}` |
| `update_available` | 跟随分组 | `[server_id] 📦 有新版本可用: {version}` |
| `update_start` | 跟随分组 | `[server_id] 📦 开始更新/重启...` |
| `tasks_command` | 跟随分组 | `[server_id] ⚙ 定时任务执行: {command}` |
| **SCAN** | | |
| `scan_found` | 跟随分组(分组默认 `false`) | `[server_id] 🔍 扫描发现 {target}: {name} [x,y,z] (距离 n)` |

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
| `push_enabled` | `true` | S 端 QQ 推送总开关(§12.1) |
| `push_chat_enabled` | `true` | CHAT 分组推送开关(§12.1) |
| `push_bot_enabled` | `true` | BOT 分组推送开关(§12.1) |
| `push_threat_enabled` | `true` | THREAT 分组推送开关(§12.1) |
| `push_server_enabled` | `true` | SERVER 分组推送开关(§12.1) |
| `push_queue_enabled` | `true` | QUEUE 分组推送开关(§12.1) |
| `push_conn_enabled` | `false` | CONN 分组推送开关(§12.1) |
| `push_ops_enabled` | `true` | OPS 分组推送开关(§12.1) |
| `push_scan_enabled` | `false` | SCAN 分组推送开关(§12.1) |
| `push_{event_type}` | `跟随分组` | 事件级推送开关(§12.1,仅显式设置时生效) |

## 14. 安全

- Token 必须修改为唯一值
- 生产环境建议绑定内网或加 TLS(`wss://`),公网需放行防火墙/安全组
- 危险动作(如封禁、删除物品)两端均应拒绝

## 15. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-06 | v1.0 | 初稿:握手/心跳/事件/任务/查询 |
| 2026-09-07 | v1.1 | 新增 `update_info`;新增 `death`/`achievement` 事件;`bot_status` 增加 `bot_name`;事件→QQ 推送规则 |
| 2026-09-08 | v1.2 | `hello`/`update_info` 新增 `account`(MC 账号名,展示用)与 `hwid`(主网卡 MAC,防盗防伪);`server_id` 默认改为自动生成 `设备名-随机8位` |
| 2026-09-09 | v1.3 | 新增事件:`player_death`(改用 `DeathMessageChatEvent` 专用事件)、`attack`、`totem_pop`/`totem_empty`、`visual_enter`/`visual_leave`、`connection_denied`、`queue_position`/`queue_complete`、`health_warning`/`health_autodisconnect`;`queue_position` 节流 ≤1 次/30s |
| 2026-09-09 | v1.3.1 | `death`(Bot 死亡)新增 `position` 死亡坐标;新增 `visual_logout`(视野内玩家下线坐标);`visual_enter`/`visual_leave`/`visual_logout` 兜底玩家实体缺失 |
| 2026-09-09 | v1.3.2 | 新增扫描功能:周期性扫描 Bot 周围指定方块与实体,首次发现上报 `scan_found`{target,name,x,y,z,distance};可配 targets/radius/interval |
| 2026-09-09 | v1.3.3 | 扫描改用游戏刻(`ClientBotTick`)驱动,无独立线程;扫描按水平半径全高范围;`targets` 新增 `redstone`(红石系列);`player_join`/`player_leave` 新增 `message` 原生描述(`xx joined/left the game`) |
| 2026-09-09 | v1.4 | 新增高价值事件推送(参照 ZenithProxy Discord 通知器结构):`queue_start`/`queue_skip`/`queue_warning`、`disconnect`、`client_connecting`/`client_connected`/`client_login_failed`/`client_reconfiguring`、`session_time_limit_warning`、`auto_reconnect`、`auto_eat_out_of_food`、`bot_death_message`、`server_restarting`、`prio_status`、`update_available`/`update_start`、`replay_started`/`replay_stopped`、`active_hours_connect`、`spawn_patrol_target`/`spawn_patrol_target_killed`、`msa_device_code`、`tasks_command`、`plugin_load_failure`;新增 QQ 推送格式表 |
| 2026-09-10 | v1.5 | 新增 `plugin_loaded` 事件(对应 Discord `PluginLoadedEvent` 通知);补充 3c3u.org 服务器消息路由说明(所有聊天统一走 `SystemChatEvent` 及解析顺序/正则);`plugin_load_failure`/`plugin_loaded` 加入 QQ 推送格式表 |
| 2026-09-10 | v1.6 | 新增 4 个代理控制连接事件(对齐 Discord `PlayerConnectedEvent`/`PlayerDisconnectedEvent`/`SpectatorConnectedEvent`/`SpectatorDisconnectedEvent`):`client_player_connected`/`client_player_disconnected`/`spectator_connected`/`spectator_disconnected`;3c3u 说明改写为「解析全部由 G 端完成、S 端只消费」;新增 §6.1 G 端 system 兜底消息再解析规划 |
| 2026-09-10 | v1.7 | §6 事件总表增加**分类**(CHAT/BOT/SERVER/QUEUE/CONN/PLUGIN/SCAN)与**语义(触发时机)**描述;§12 推送改为**三层开关体系**(总开关 `push_enabled` → 分组开关 `push_{cn}_enabled` → 事件级开关 `push_{event_type}`),覆盖 §13 配置表;3c3u 私聊/公共解析说明同步为 `ChatMessageParser` 当前实现(`You` sender 语义、`\|\|` 保留、`>` 保留为消息) |
| 2026-09-10 | v1.8 | **成就解析修正**:兼容 `[成就名]` 方括号格式(3c3u 实际为 `has made the advancement [xxx]`,此前只有冒号);**新增死亡解析**:`victim was/were/got/died ...` → `player_death`(输出 `victim`/`cause`/`killer`/`weapon`);§6.1 兜底表移除已归类的服务器死亡广播 |
| 2026-09-10 | v1.8.1 | G 端解析增加**translate 键预判**(依据原始组件):`chat.type.advancement.*` → 成就、`death.*` 或含 `%1$s` 模板 → 玩家死亡,覆盖 `was/were/got/died` 前缀之外的死亡动词(坠亡/溺水等);adventure 5.0 API(`TranslationArgument.arguments()`) |
| 2026-09-10 | v1.9 | **分组重定义**:新增 `THREAT` 分类(`attack`/`totem_pop`/`totem_empty`,Bot 遭攻击威胁,独立开关 `push_threat_enabled`,避免被 SERVER 总开关连带关闭);`spawn_patrol_target`/`spawn_patrol_target_killed` 移入 `BOT`(Bot 主动防御模块);`PLUGIN` 更名为 `OPS`(含 `update_*`/`tasks_command` 运维事件,开关改 `push_ops_enabled`);`connection_denied` 保留在 SERVER(代理安全,默认推送) |