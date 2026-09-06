package org.example.module;

import com.github.rfresh2.EventConsumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.server.ServerPlayerConnectedEvent;
import com.zenith.event.server.ServerPlayerDisconnectedEvent;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.ENTITY_DATA;
import static org.example.RaspiPlugin.LOG;
import static org.example.RaspiPlugin.PLUGIN_CONFIG;

public class BridgeModule extends Module {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Bridge-WS");
        t.setDaemon(true);
        return t;
    });
    private final StringBuilder textBuffer = new StringBuilder();
    private final WebSocket.Listener listener = new WebSocket.Listener() {
        @Override
        public void onOpen(WebSocket webSocket) {
            BridgeModule.this.webSocket = webSocket;
            connected = true;
            LOG.info("[Bridge] Connected to {}", PLUGIN_CONFIG.bridge.wsUrl);
            startHeartbeat();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                handleIncomingMessage(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("[Bridge] Connection closed ({}) {}", statusCode, reason);
            handleClose();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lastError = error.getMessage();
            LOG.error("[Bridge] WebSocket error: {}", error.getMessage());
            if (webSocket == null) {
                scheduleReconnect();
            } else {
                webSocket.abort();
            }
        }
    };
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile long lastHeartbeatMs = -1L;
    private volatile String lastError;
    private ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> heartbeatFuture;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.bridge.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(PublicChatEvent.class, this::handlePublicChatEvent),
            of(WhisperChatEvent.class, this::handleWhisperChatEvent),
            of(SystemChatEvent.class, this::handleSystemChatEvent),
            of(ServerPlayerConnectedEvent.class, this::handlePlayerConnected),
            of(ServerPlayerDisconnectedEvent.class, this::handlePlayerDisconnected),
            of(ClientOnlineEvent.class, this::handleBotOnline),
            of(ClientDisconnectEvent.class, this::handleBotOffline),
            of(ClientDeathEvent.class, this::handleBotDeath)
        );
    }

    @Override
    public void onEnable() {
        connect();
    }

    @Override
    public void onDisable() {
        disconnect();
    }

    public boolean isBridgeConnected() {
        return connected;
    }

    public long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public String getLastError() {
        return lastError;
    }

    public synchronized void connect() {
        if (isBridgeConnected()) return;
        cancelReconnect();
        LOG.info("[Bridge] Connecting to {}...", PLUGIN_CONFIG.bridge.wsUrl);
        try {
            httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + PLUGIN_CONFIG.bridge.sharedToken)
                .buildAsync(URI.create(PLUGIN_CONFIG.bridge.wsUrl), listener)
                .whenComplete((socket, ex) -> {
                    if (ex != null) {
                        lastError = ex.getMessage();
                        LOG.error("[Bridge] Connection failed: {}", ex.getMessage());
                        scheduleReconnect();
                    }
                });
        } catch (final Exception e) {
            lastError = e.getMessage();
            LOG.error("[Bridge] Connection error: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    public synchronized void disconnect() {
        cancelReconnect();
        close();
    }

    public void reconnect() {
        if (!isEnabled()) return;
        disconnect();
        connect();
    }

    public boolean sendMessage(final String message) {
        if (!connected) return false;
        var ws = webSocket;
        if (ws == null) return false;
        ws.sendText(message, true);
        return true;
    }

    // **************** events -> AstrBot ****************

    private void handlePublicChatEvent(final PublicChatEvent event) {
        if (isOwnMessage(event.sender().getName(), event.message())) return;
        var data = new JsonObject();
        data.addProperty("sender", event.sender().getName());
        data.addProperty("message", event.message());
        sendEvent("chat", data);
    }

    private void handleWhisperChatEvent(final WhisperChatEvent event) {
        var data = new JsonObject();
        data.addProperty("outgoing", event.outgoing());
        data.addProperty("sender", event.sender().getName());
        data.addProperty("receiver", event.receiver().getName());
        data.addProperty("message", event.message());
        sendEvent("whisper", data);
    }

    private void handleSystemChatEvent(final SystemChatEvent event) {
        var data = new JsonObject();
        data.addProperty("message", event.message());
        sendEvent("system", data);
    }

    private void handlePlayerConnected(final ServerPlayerConnectedEvent event) {
        var data = new JsonObject();
        data.addProperty("player", event.playerEntry().getName());
        
        sendEvent("player_join", data);
    }

    private void handlePlayerDisconnected(final ServerPlayerDisconnectedEvent event) {
        var data = new JsonObject();
        data.addProperty("player", event.playerEntry().getName());
        sendEvent("player_leave", data);
    }

    private void handleBotOnline(final ClientOnlineEvent event) {
        sendBotStatus("online");
    }

    private void handleBotOffline(final ClientDisconnectEvent event) {
        sendBotStatus("offline");
    }

    private void handleBotDeath(final ClientDeathEvent event) {
        sendBotStatus("dead");
    }

    private void sendBotStatus(final String status) {
        var data = new JsonObject();
        data.addProperty("status", status);
        try {
            var pc = CACHE.getPlayerCache();
            if (pc.getThePlayer() != null) {
                data.addProperty("health", pc.getThePlayer().getHealth());
                data.addProperty("food", pc.getThePlayer().getFood());
            }
            var position = new JsonObject();
            position.addProperty("x", pc.getX());
            position.addProperty("y", pc.getY());
            position.addProperty("z", pc.getZ());
            data.add("position", position);
        } catch (final Exception e) {
            LOG.debug("[Bridge] Unable to read bot state: {}", e.getMessage());
        }
        sendEvent("bot_status", data);
    }

    private void sendEvent(final String eventType, final JsonObject data) {
        var event = new JsonObject();
        event.addProperty("type", "event");
        event.addProperty("server_id", PLUGIN_CONFIG.bridge.serverId);
        event.addProperty("event_type", eventType);
        event.add("data", data);
        event.addProperty("timestamp", System.currentTimeMillis() / 1000);
        sendMessage(event.toString());
    }

    private boolean isOwnMessage(final String sender, final String message) {
        return sender != null && sender.equalsIgnoreCase(CONFIG.authentication.username)
            || (message != null && message.startsWith(CONFIG.authentication.username + ":"));
    }

    // **************** AstrBot -> tasks ****************

    private void handleIncomingMessage(final String raw) {
        LOG.info("[Bridge] Received: {}", raw);
        try {
            final JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            final String type = msg.has("type") ? msg.get("type").getAsString() : null;
            if ("task".equals(type)) {
                handleTask(msg);
            } else if ("query".equals(type)) {
                handleQuery(msg);
            } else {
                LOG.info("[Bridge] Ignoring unknown message type: {}", type);
            }
        } catch (final Exception e) {
            LOG.error("[Bridge] Failed to parse message: {}", e.getMessage());
        }
    }

    private void handleTask(final JsonObject task) {
        final String taskId = getStringOrNull(task, "task_id");
        final String action = getStringOrNull(task, "action");
        if (action == null) {
            sendTaskResult(taskId, false, "missing action");
            return;
        }
        final JsonObject params = task.has("params") && !task.get("params").isJsonNull()
            ? task.getAsJsonObject("params")
            : null;
        LOG.info("[Bridge] Task {} action={}", taskId, action);
        switch (action) {
            case "send_chat" -> handleSendChatTask(taskId, params);
            case "move_to" -> handleMoveToTask(taskId, params);
            case "attack_nearest" -> handleAttackNearestTask(taskId, params);
            default -> sendTaskResult(taskId, false, "unsupported action: " + action);
        }
    }

    private void handleSendChatTask(final String taskId, final JsonObject params) {
        if (params == null || !params.has("message") || params.get("message").isJsonNull()) {
            sendTaskResult(taskId, false, "missing message param");
            return;
        }
        final String message = params.get("message").getAsString();
        if (message.isBlank()) {
            sendTaskResult(taskId, false, "empty message");
            return;
        }
        sendClientPacketAsync(new ServerboundChatPacket(message));
        LOG.info("[Bridge] Task {}: sent chat: {}", taskId, message);
        sendTaskResult(taskId, true, "chat sent");
    }

    private void handleMoveToTask(final String taskId, final JsonObject params) {
        if (params == null || !params.has("x") || !params.has("y") || !params.has("z")) {
            sendTaskResult(taskId, false, "missing x/y/z params");
            return;
        }
        if (!Proxy.getInstance().isConnected()) {
            sendTaskResult(taskId, false, "bot not connected to server");
            return;
        }
        final int x = params.get("x").getAsInt();
        final int y = params.get("y").getAsInt();
        final int z = params.get("z").getAsInt();
        final var future = BARITONE.pathTo(x, y, z);
        if (future.isAccepted()) {
            LOG.info("[Bridge] Task {}: pathing to {}, {}, {}", taskId, x, y, z);
            sendTaskResult(taskId, true, "pathing to " + x + ", " + y + ", " + z);
        } else {
            sendTaskResult(taskId, false, "pathing request rejected");
        }
    }

    private void handleAttackNearestTask(final String taskId, final JsonObject params) {
        if (!Proxy.getInstance().isConnected()) {
            sendTaskResult(taskId, false, "bot not connected to server");
            return;
        }
        final double radius = params != null && params.has("radius") ? params.get("radius").getAsDouble() : 16.0;
        double px = CACHE.getPlayerCache().getX();
        double py = CACHE.getPlayerCache().getY();
        double pz = CACHE.getPlayerCache().getZ();
        EntityLiving best = null;
        double bestDist = Double.MAX_VALUE;
        for (final var e : CACHE.getEntityCache().getEntities().values()) {
            if (e instanceof EntityPlayer || !(e instanceof EntityLiving living)) continue;
            final var data = ENTITY_DATA.getEntityData(e.getEntityType());
            if (data == null || !data.attackable()) continue;
            final double dist = distanceSq(px, py, pz, e.getX(), e.getY(), e.getZ());
            if (dist <= radius * radius && dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        if (best == null) {
            sendTaskResult(taskId, false, "no attackable entity found within " + radius + " blocks");
            return;
        }
        BARITONE.leftClickEntity(best);
        LOG.info("[Bridge] Task {}: attacking {} at distance {}", taskId, entityName(best), Math.round(Math.sqrt(bestDist) * 10.0) / 10.0);
        sendTaskResult(taskId, true, "attacking " + entityName(best));
    }

    private void handleQuery(final JsonObject query) {
        final String queryId = getStringOrNull(query, "query_id");
        final String resource = getStringOrNull(query, "resource");
        if (resource == null) {
            sendQueryResult(queryId, false, null, "missing resource");
            return;
        }
        final JsonObject params = query.has("params") && !query.get("params").isJsonNull()
            ? query.getAsJsonObject("params")
            : null;
        LOG.info("[Bridge] Query {} resource={}", queryId, resource);
        switch (resource) {
            case "online_players" -> handleOnlinePlayersQuery(queryId);
            case "bot_status" -> handleBotStatusQuery(queryId);
            case "inventory" -> handleInventoryQuery(queryId);
            case "nearby_entities" -> handleNearbyEntitiesQuery(queryId, params);
            default -> sendQueryResult(queryId, false, null, "unsupported resource: " + resource);
        }
    }

    private void handleOnlinePlayersQuery(final String queryId) {
        var data = new JsonObject();
        var players = new JsonArray();
        for (final PlayerListEntry entry : CACHE.getTabListCache().getEntries()) {
            var player = new JsonObject();
            player.addProperty("name", entry.getName());
            player.addProperty("ping", entry.getLatency());
            players.add(player);
        }
        data.add("players", players);
        data.addProperty("count", players.size());
        sendQueryResult(queryId, true, data, null);
    }

    private void handleBotStatusQuery(final String queryId) {
        var pc = CACHE.getPlayerCache();
        var data = new JsonObject();
        data.addProperty("status", Proxy.getInstance().isConnected() ? "online" : "offline");
        if (pc.getThePlayer() != null) {
            data.addProperty("health", pc.getThePlayer().getHealth());
            data.addProperty("food", pc.getThePlayer().getFood());
        }
        var position = new JsonObject();
        position.addProperty("x", pc.getX());
        position.addProperty("y", pc.getY());
        position.addProperty("z", pc.getZ());
        data.add("position", position);
        data.addProperty("heldItemSlot", pc.getHeldItemSlot());
        sendQueryResult(queryId, true, data, null);
    }

    private void handleInventoryQuery(final String queryId) {
        final var inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getContents();
        var data = new JsonObject();
        var items = new JsonArray();
        for (int i = 0; i < inventory.size(); i++) {
            final var stack = inventory.get(i);
            if (stack == null || stack.getId() == 0) continue;
            var item = new JsonObject();
            item.addProperty("slot", i);
            item.addProperty("id", stack.getId());
            final var itemData = ItemRegistry.REGISTRY.get(stack.getId());
            final String itemName = itemData != null ? itemData.name() : "minecraft:" + stack.getId();
            item.addProperty("name", itemName);
            item.addProperty("count", stack.getAmount());
            items.add(item);
        }
        data.add("items", items);
        data.addProperty("count", items.size());
        sendQueryResult(queryId, true, data, null);
    }

    private void handleNearbyEntitiesQuery(final String queryId, final JsonObject params) {
        final double radius = params != null && params.has("radius") ? params.get("radius").getAsDouble() : 32.0;
        double px = CACHE.getPlayerCache().getX();
        double py = CACHE.getPlayerCache().getY();
        double pz = CACHE.getPlayerCache().getZ();
        var entities = new JsonArray();
        CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e.getEntityId() != CACHE.getPlayerCache().getEntityId())
            .filter(e -> distanceSq(px, py, pz, e.getX(), e.getY(), e.getZ()) <= radius * radius)
            .sorted(Comparator.comparingDouble(e -> distanceSq(px, py, pz, e.getX(), e.getY(), e.getZ())))
            .limit(64)
            .forEach(e -> {
                var entry = new JsonObject();
                entry.addProperty("entity_id", e.getEntityId());
                entry.addProperty("type", entityName(e));
                if (e instanceof EntityPlayer ep && ep.getUuid() != null) {
                    CACHE.getTabListCache().get(ep.getUuid())
                        .ifPresent(pe -> entry.addProperty("player", pe.getName()));
                }
                entry.addProperty("distance", Math.round(Math.sqrt(distanceSq(px, py, pz, e.getX(), e.getY(), e.getZ())) * 10.0) / 10.0);
                entry.addProperty("x", Math.round(e.getX() * 10.0) / 10.0);
                entry.addProperty("y", Math.round(e.getY() * 10.0) / 10.0);
                entry.addProperty("z", Math.round(e.getZ() * 10.0) / 10.0);
                entities.add(entry);
            });
        var data = new JsonObject();
        data.add("entities", entities);
        data.addProperty("count", entities.size());
        sendQueryResult(queryId, true, data, null);
    }

    private static String entityName(final com.zenith.cache.data.entity.Entity entity) {
        final var data = ENTITY_DATA.getEntityData(entity.getEntityType());
        return data != null ? data.name() : entity.getEntityType().name().toLowerCase();
    }

    private static double distanceSq(final double ax, final double ay, final double az,
                                     final double bx, final double by, final double bz) {
        final double dx = ax - bx;
        final double dy = ay - by;
        final double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private void sendTaskResult(final String taskId, final boolean success, final String message) {
        var result = new JsonObject();
        result.addProperty("type", "task_result");
        if (taskId != null) result.addProperty("task_id", taskId);
        result.addProperty("success", success);
        result.addProperty("error_message", message);
        sendMessage(result.toString());
    }

    private void sendQueryResult(final String queryId, final boolean success, final JsonObject data, final String message) {
        var result = new JsonObject();
        result.addProperty("type", "query_result");
        if (queryId != null) result.addProperty("query_id", queryId);
        result.addProperty("success", success);
        if (data != null) result.add("data", data);
        result.addProperty("error_message", message);
        sendMessage(result.toString());
    }

    private static String getStringOrNull(final JsonObject obj, final String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    // **************** connection lifecycle ****************

    private synchronized void handleClose() {
        close();
        scheduleReconnect();
    }

    private synchronized void close() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        var ws = webSocket;
        webSocket = null;
        connected = false;
        if (ws != null) {
            ws.abort();
        }
    }

    private void scheduleReconnect() {
        cancelReconnect();
        if (!isEnabled()) return;
        LOG.info("[Bridge] Reconnecting in {}s...", PLUGIN_CONFIG.bridge.reconnectIntervalSeconds);
        reconnectFuture = executor.schedule(this::connect,
            PLUGIN_CONFIG.bridge.reconnectIntervalSeconds, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    private void startHeartbeat() {
        if (heartbeatFuture != null) return;
        heartbeatFuture = executor.scheduleAtFixedRate(this::sendHeartbeat,
            PLUGIN_CONFIG.bridge.heartbeatIntervalSeconds,
            PLUGIN_CONFIG.bridge.heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    private void sendHeartbeat() {
        if (!isBridgeConnected()) return;
        var obj = new JsonObject();
        obj.addProperty("type", "heartbeat");
        obj.addProperty("server_id", PLUGIN_CONFIG.bridge.serverId);
        obj.addProperty("timestamp", System.currentTimeMillis() / 1000);
        if (sendMessage(obj.toString())) {
            lastHeartbeatMs = System.currentTimeMillis();
        }
    }
}