package org.example.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import org.example.BuildConstants;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.ENTITY_DATA;
import static org.example.RaspiPlugin.LOG;
import static org.example.RaspiPlugin.PLUGIN_CONFIG;

public class QQBot {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Bridge-WS");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> reportedTargets = new HashSet<>();
    private int scanTickCounter;
    private boolean tickSpamLogged;
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile String effectiveServerId;
    private volatile String hwidCache;
    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean handshakeComplete;
    private volatile long lastHeartbeatMs = -1L;
    private volatile long lastHeartbeatAckMs = -1L;
    private volatile String lastError;
    private ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final WebSocket.Listener listener = new WebSocket.Listener() {
        @Override
        public void onOpen(WebSocket webSocket) {
            QQBot.this.webSocket = webSocket;
            connected = true;
            lastError = null;
            LOG.info("[Bridge] Connected to {}", PLUGIN_CONFIG.bridge.wsUrl);
            sendHello();
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
            lastError = describeFailure(error);
            LOG.error("[Bridge] WebSocket error: {}", lastError);
            if (webSocket == null) {
                scheduleReconnect();
            } else {
                webSocket.abort();
            }
        }
    };

    // **************** connection lifecycle ****************

    public boolean isBridgeConnected() {
        return connected;
    }

    public long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public long getLastHeartbeatAckMs() {
        return lastHeartbeatAckMs;
    }

    public boolean isHandshakeComplete() {
        return handshakeComplete;
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
                .connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + PLUGIN_CONFIG.bridge.token)
                .buildAsync(URI.create(PLUGIN_CONFIG.bridge.wsUrl), listener)
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((socket, ex) -> {
                    if (ex != null) {
                        onConnectFailure(ex);
                    }
                });
        } catch (final Exception e) {
            onConnectFailure(e);
        }
    }

    private void onConnectFailure(final Throwable ex) {
        final String error = describeFailure(ex);
        final boolean changed = !error.equals(lastError);
        lastError = error;
        if (changed) {
            LOG.error("[Bridge] Connection failed: {}", error);
            LOG.warn("[Bridge] Connection to {} failed. Check that: 1) the AstrBot WebSocket server is running and listening on 0.0.0.0:{} 2) the server firewall / cloud security group allows inbound TCP on that port 3) the scheme matches (ws:// for plain, wss:// for TLS)",
                PLUGIN_CONFIG.bridge.wsUrl, wsPort(PLUGIN_CONFIG.bridge.wsUrl));
        }
        scheduleReconnect();
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

    public synchronized void sendUpdateInfo() {
        if (!isBridgeConnected() || !handshakeComplete) return;
        var info = new JsonObject();
        info.addProperty("type", "update_info");
        info.addProperty("server_id", getEffectiveServerId());
        info.addProperty("account", currentBotName());
        info.addProperty("hwid", getHwid());
        info.addProperty("mod_version", BuildConstants.VERSION);
        var capabilities = new JsonArray();
        capabilities.add("chat");
        capabilities.add("whisper");
        capabilities.add("query");
        capabilities.add("task");
        info.add("capabilities", capabilities);
        if (sendMessage(info.toString())) {
            LOG.info("[Bridge] Sent update_info (server_id={})", getEffectiveServerId());
        }
    }

    // **************** event sending ****************

    public void sendEvent(final String eventType, final JsonObject data) {
        if (!isBridgeConnected() || !handshakeComplete) return;
        var event = new JsonObject();
        event.addProperty("type", "event");
        event.addProperty("server_id", getEffectiveServerId());
        event.addProperty("event_type", eventType);
        event.add("data", data);
        event.addProperty("timestamp", System.currentTimeMillis() / 1000);
        if (PLUGIN_CONFIG.bridge.eventDebugLogging) {
            LOG.info("[Bridge] [Debug] Event sent: {} {}", eventType, event);
        }
        sendMessage(event.toString());
    }

    // **************** server ID / HWID ****************

    public String getEffectiveServerId() {
        final String id = PLUGIN_CONFIG.bridge.serverId;
        if (id != null && !id.isBlank() && !"default".equalsIgnoreCase(id)) return id;
        if (effectiveServerId == null) {
            synchronized (this) {
                if (effectiveServerId == null) {
                    effectiveServerId = generateInstanceId();
                }
            }
        }
        return effectiveServerId;
    }

    private String generateInstanceId() {
        final String existing = PLUGIN_CONFIG.bridge.instanceId;
        if (existing != null && !existing.isBlank()) return existing;
        final String host = sanitizeHostname(resolveHostname());
        final String suffix = host.isBlank() ? UUID.randomUUID().toString().substring(0, 8)
            : "-" + UUID.randomUUID().toString().substring(0, 8);
        final String generated = host + suffix;
        PLUGIN_CONFIG.bridge.instanceId = generated;
        return generated;
    }

    private String resolveHostname() {
        final String env = System.getenv().getOrDefault("COMPUTERNAME",
            System.getenv().getOrDefault("HOSTNAME", ""));
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (final Exception e) {
            return "";
        }
    }

    private static String sanitizeHostname(final String host) {
        final String clean = host == null ? "" : host.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        return clean.length() > 20 ? clean.substring(0, 20) : clean;
    }

    public String getHwid() {
        if (hwidCache == null) {
            synchronized (this) {
                if (hwidCache == null) {
                    hwidCache = resolveHwid();
                }
            }
        }
        return hwidCache;
    }

    private String resolveHwid() {
        try {
            final Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                final NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) continue;
                final byte[] mac = nic.getHardwareAddress();
                if (mac != null && mac.length >= 6) {
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format(Locale.ROOT, "%02x", mac[i]));
                    }
                    return sb.toString();
                }
            }
        } catch (final Exception e) {
            LOG.debug("[Bridge] Unable to resolve MAC address: {}", e.getMessage());
        }
        return "";
    }

    // **************** bot state helpers ****************

    public String currentBotName() {
        try {
            final var profile = CACHE.getProfileCache().getProfile();
            if (profile != null && profile.getName() != null && !profile.getName().isBlank()) {
                return profile.getName();
            }
        } catch (final Exception e) {
            LOG.debug("[Bridge] Unable to read profile name: {}", e.getMessage());
        }
        return CONFIG.authentication.username;
    }

    public String getCurrentBotName() {
        return currentBotName();
    }

    public boolean isOwnMessage(final String sender, final String message) {
        return sender != null && sender.equalsIgnoreCase(CONFIG.authentication.username)
            || (message != null && message.startsWith(CONFIG.authentication.username + ":"));
    }

    public String entryName(final PlayerListEntry entry) {
        try {
            return entry != null ? entry.getName() : "?";
        } catch (final Exception e) {
            return "?";
        }
    }

    public String playerDisplayName(final EntityPlayer player) {
        try {
            var entry = CACHE.getTabListCache().get(player.getUuid()).orElse(null);
            if (entry != null) return entry.getName();
        } catch (final Exception ignored) { }
        return player.getUuid().toString();
    }

    public void sendBotStatus(final String status) {
        var data = new JsonObject();
        data.addProperty("status", status);
        data.addProperty("bot_name", currentBotName());
        try {
            var pc = CACHE.getPlayerCache();
            if (pc.getThePlayer() != null) {
                data.addProperty("health", pc.getThePlayer().getHealth());
                data.addProperty("food", pc.getThePlayer().getFood());
            }
            var position = currentBotPosition();
            if (position != null) data.add("position", position);
        } catch (final Exception e) {
            LOG.debug("[Bridge] Unable to read bot state: {}", e.getMessage());
        }
        sendEvent("bot_status", data);
    }

    public JsonObject currentBotPosition() {
        try {
            var pc = CACHE.getPlayerCache();
            var position = new JsonObject();
            position.addProperty("x", pc.getX());
            position.addProperty("y", pc.getY());
            position.addProperty("z", pc.getZ());
            return position;
        } catch (final Exception e) {
            LOG.debug("[Bridge] Unable to read bot state: {}", e.getMessage());
            return null;
        }
    }

    public void sendVisualEvent(final String eventType, final PlayerListEntry entry, final EntityPlayer player) {
        var data = new JsonObject();
        data.addProperty("player", entry != null ? entry.getName() : playerDisplayName(player));
        if (player != null) {
            try {
                var block = player.blockPos();
                data.addProperty("x", block.x());
                data.addProperty("y", block.y());
                data.addProperty("z", block.z());
            } catch (final Exception ignored) { }
        }
        sendEvent(eventType, data);
    }

    // **************** debug ****************

    public void debugEvent(final String eventType, final Object detail) {
        if (PLUGIN_CONFIG.bridge.eventDebugLogging) {
            LOG.info("[Bridge] [Debug] Event received: {} {}", eventType, detail);
        }
    }

    // **************** scan ****************

    public void handleScanTick() {
        if (PLUGIN_CONFIG.bridge.eventDebugLogging && !PLUGIN_CONFIG.bridge.scan.enabled) {
            if (!tickSpamLogged) {
                tickSpamLogged = true;
                LOG.info("[Bridge] [Debug] Event received: ClientBotTick (spam throttled)");
            }
            return;
        }
        if (!PLUGIN_CONFIG.bridge.scan.enabled) return;
        int intervalTicks = Math.max(20, Math.min(1200, PLUGIN_CONFIG.bridge.scan.intervalSeconds * 20));
        if (++scanTickCounter < intervalTicks) return;
        scanTickCounter = 0;
        scanPass();
    }

    private void scanPass() {
        try {
            final List<String> targets = PLUGIN_CONFIG.bridge.scan.targets;
            if (targets.isEmpty()) return;
            final var pc = CACHE.getPlayerCache();
            final var thePlayer = pc.getThePlayer();
            if (thePlayer == null) return;
            final var center = thePlayer.blockPos();
            final Set<String> seen = new HashSet<>();
            final int radius = Math.max(1, Math.min(64, PLUGIN_CONFIG.bridge.scan.radius));

            boolean entityTargets = targets.contains("player") || targets.contains("ender_pearl") || targets.contains("item_frame");
            boolean blockTargets = targets.contains("chest") || targets.contains("ender_chest")
                || targets.contains("shulker_box") || targets.contains("soul_sand")
                || targets.contains("bubble_column") || targets.contains("sign") || targets.contains("redstone");
            if (entityTargets) scanEntities(center, radius, seen, targets);
            if (blockTargets) scanBlocks(center, radius, seen, targets);

            synchronized (reportedTargets) {
                reportedTargets.removeIf(key -> !seen.contains(key));
            }
        } catch (final Exception e) {
            LOG.debug("[Bridge] Scan pass failed: {}", e.getMessage());
        }
    }

    private void scanEntities(final BlockPos center, final int radius, final Set<String> seen, final List<String> targets) {
        for (final com.zenith.cache.data.entity.Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (!(entity instanceof final EntityLiving living)) continue;
            final String target = matchScanEntityTarget(living, targets);
            if (target == null) continue;
            final var pos = living.blockPos();
            final String key = target + ":" + (living.getUuid() != null ? living.getUuid() : living.getEntityId());
            reportScan(target, scanDisplayName(target, living), pos.x(), pos.y(), pos.z(), center, key, seen);
        }
    }

    private String matchScanEntityTarget(final EntityLiving living, final List<String> targets) {
        if (living instanceof EntityPlayer) return targets.contains("player") ? "player" : null;
        final EntityType type = living.getEntityType();
        if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME) {
            return targets.contains("item_frame") ? "item_frame" : null;
        }
        if (type == EntityType.ITEM && targets.contains("ender_pearl")) {
            final ItemStack item = living.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
            if (item != null && ItemRegistry.REGISTRY.get(item.getId()) == ItemRegistry.ENDER_PEARL) {
                return "ender_pearl";
            }
        }
        return null;
    }

    private void scanBlocks(final BlockPos center, final int radius, final Set<String> seen, final List<String> targets) {
        final var chunkCache = CACHE.getChunkCache();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            final int chunkX = x >> 4;
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                final var chunk = chunkCache.get(chunkX, z >> 4);
                if (chunk == null) continue;
                for (int y = chunk.minY(); y <= chunk.maxY(); y += 16) {
                    for (int yOff = 0; yOff < 16; yOff++) {
                        final int stateId = chunk.getBlockStateId(x & 15, y + yOff, z & 15);
                        if (stateId <= 0) continue;
                        final Block block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
                        final String target = matchScanBlockTarget(block, targets);
                        if (target == null) continue;
                        final String key = target + ":" + x + "," + (y + yOff) + "," + z;
                        reportScan(target, block.name(), x, y + yOff, z, center, key, seen);
                    }
                }
            }
        }
    }

    private String matchScanBlockTarget(final Block block, final List<String> targets) {
        if (block == null) return null;
        final String name = block.name();
        if (targets.contains("chest") && (name.equals("minecraft:chest") || name.equals("minecraft:trapped_chest"))) return "chest";
        if (targets.contains("ender_chest") && name.equals("minecraft:ender_chest")) return "ender_chest";
        if (targets.contains("shulker_box") && name.endsWith("_shulker_box")) return "shulker_box";
        if (targets.contains("soul_sand") && name.equals("minecraft:soul_sand")) return "soul_sand";
        if (targets.contains("bubble_column") && name.equals("minecraft:bubble_column")) return "bubble_column";
        if (targets.contains("sign") && name.endsWith("_sign")) return "sign";
        if (targets.contains("redstone") && name.contains("redstone")) return "redstone";
        return null;
    }

    private String scanDisplayName(final String target, final EntityLiving living) {
        if (living instanceof final EntityPlayer player) {
            final String name = playerDisplayName(player);
            if (name != null && !name.isEmpty()) return name;
        }
        return target;
    }

    private void reportScan(final String target, final String name, final int x, final int y, final int z,
                            final BlockPos center, final String key, final Set<String> seen) {
        seen.add(key);
        synchronized (reportedTargets) {
            if (!reportedTargets.add(key)) return;
        }
        var data = new JsonObject();
        data.addProperty("target", target);
        data.addProperty("name", name);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        final double dx = center.x() - x;
        final double dy = center.y() - y;
        final double dz = center.z() - z;
        data.addProperty("distance", (int) Math.sqrt(dx * dx + dy * dy + dz * dz));
        sendEvent("scan_found", data);
    }

    // **************** task/query handling ****************

    private void handleIncomingMessage(final String raw) {
        LOG.debug("[Bridge] Received: {}", raw);
        try {
            final JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            final String type = msg.has("type") ? msg.get("type").getAsString() : null;
            if ("hello_ack".equals(type)) {
                handshakeComplete = true;
                LOG.info("[Bridge] Handshake complete with AstrBot (server_id={})", getStringOrNull(msg, "server_id"));
            } else if ("update_info_ack".equals(type)) {
                LOG.info("[Bridge] AstrBot confirmed update_info (server_id={})", getStringOrNull(msg, "server_id"));
            } else if ("heartbeat_ack".equals(type)) {
                lastHeartbeatAckMs = System.currentTimeMillis();
                LOG.debug("[Bridge] Heartbeat acknowledged");
            } else if ("task".equals(type)) {
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
        final String safe = sanitizeChatMessage(message);
        if (safe.isBlank()) {
            sendTaskResult(taskId, false, "message contains only illegal characters");
            return;
        }
        if (!safe.equals(message)) {
            if (PLUGIN_CONFIG.bridge.eventDebugLogging) {
                LOG.info("[Bridge] [Debug] Task {}: sanitized chat: {}", taskId, safe);
            }
        }
        sendClientPacketAsync(new ServerboundChatPacket(safe));
        LOG.info("[Bridge] Task {}: sent chat: {}", taskId, safe);
        sendTaskResult(taskId, true, "chat sent");
    }

    static String sanitizeChatMessage(final String message) {
        final StringBuilder sb = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            final char c = message.charAt(i);
            if (c == '\u00a7') continue;
            if (c < 0x20 || c == 0x7f) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().replaceAll("[ \t]+", " ").trim();
        if (result.length() > 256) result = result.substring(0, 256);
        return result;
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

    static String entityName(final com.zenith.cache.data.entity.Entity entity) {
        final var data = ENTITY_DATA.getEntityData(entity.getEntityType());
        return data != null ? data.name() : entity.getEntityType().name().toLowerCase();
    }

    static double distanceSq(final double ax, final double ay, final double az,
                              final double bx, final double by, final double bz) {
        final double dx = ax - bx;
        final double dy = ay - by;
        final double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    public void sendTaskResult(final String taskId, final boolean success, final String message) {
        var result = new JsonObject();
        result.addProperty("type", "task_result");
        if (taskId != null) result.addProperty("task_id", taskId);
        result.addProperty("success", success);
        result.addProperty("error_message", message);
        sendMessage(result.toString());
    }

    public void sendQueryResult(final String queryId, final boolean success, final JsonObject data, final String message) {
        var result = new JsonObject();
        result.addProperty("type", "query_result");
        if (queryId != null) result.addProperty("query_id", queryId);
        result.addProperty("success", success);
        if (data != null) result.add("data", data);
        result.addProperty("error_message", message);
        sendMessage(result.toString());
    }

    // **************** heartbeat / reconnect ****************

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
        handshakeComplete = false;
        lastHeartbeatAckMs = -1L;
        if (ws != null) {
            ws.abort();
        }
    }

    private void scheduleReconnect() {
        cancelReconnect();
        if (!isEnabled()) return;
        final long delayMs = PLUGIN_CONFIG.bridge.reconnectIntervalSeconds * 1000L
            + (long) (Math.random() * 1000);
        LOG.info("[Bridge] Reconnecting in {}s...", PLUGIN_CONFIG.bridge.reconnectIntervalSeconds);
        reconnectFuture = executor.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
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

    private void sendHello() {
        var hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("server_id", getEffectiveServerId());
        hello.addProperty("account", currentBotName());
        hello.addProperty("hwid", getHwid());
        hello.addProperty("server_name", PLUGIN_CONFIG.bridge.serverName);
        hello.addProperty("mod_version", BuildConstants.VERSION);
        var capabilities = new JsonArray();
        capabilities.add("chat");
        capabilities.add("whisper");
        capabilities.add("query");
        capabilities.add("task");
        hello.add("capabilities", capabilities);
        sendMessage(hello.toString());
        LOG.debug("[Bridge] Sent hello (server_id={}, account={}, server_name={}, mod_version={})",
            getEffectiveServerId(), currentBotName(), PLUGIN_CONFIG.bridge.serverName, BuildConstants.VERSION);
    }

    private void sendHeartbeat() {
        if (!isBridgeConnected()) return;
        var obj = new JsonObject();
        obj.addProperty("type", "heartbeat");
        obj.addProperty("server_id", getEffectiveServerId());
        obj.addProperty("timestamp", System.currentTimeMillis() / 1000);
        if (sendMessage(obj.toString())) {
            lastHeartbeatMs = System.currentTimeMillis();
        }
        checkHeartbeatTimeout();
    }

    private void checkHeartbeatTimeout() {
        if (!isBridgeConnected()) return;
        final long lastAck = lastHeartbeatAckMs;
        if (lastAck < 0) return;
        final long timeoutMillis = 3L * PLUGIN_CONFIG.bridge.heartbeatIntervalSeconds * 1000L;
        if (System.currentTimeMillis() - lastAck > timeoutMillis) {
            LOG.warn("[Bridge] No heartbeat_ack for {}s, connection considered dead, reconnecting...",
                3L * PLUGIN_CONFIG.bridge.heartbeatIntervalSeconds);
            close();
            scheduleReconnect();
        }
    }

    public void cleanup() {
        disconnect();
        reportedTargets.clear();
    }

    // **************** util ****************

    private static boolean isEnabled() {
        return PLUGIN_CONFIG.bridge.enabled;
    }

    static String getStringOrNull(final JsonObject obj, final String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static String describeFailure(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String wsPort(final String wsUrl) {
        try {
            return String.valueOf(URI.create(wsUrl).getPort() == -1 ? 80 : URI.create(wsUrl).getPort());
        } catch (final Exception e) {
            return "?";
        }
    }

    private static void sendClientPacketAsync(final org.geysermc.mcprotocollib.network.packet.Packet packet) {
        try {
            Proxy.getInstance().getClient().sendAsync(packet);
        } catch (final Exception e) {
            LOG.debug("[Bridge] Failed to send packet: {}", e.getMessage());
        }
    }
}
