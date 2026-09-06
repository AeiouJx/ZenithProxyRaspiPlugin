package org.example.web.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zenith.Globals;
import com.zenith.command.api.CommandContext;
import com.zenith.discord.EmbedSerializer;
import com.zenith.util.ComponentSerializer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson3;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.example.RaspiPlugin;
import org.example.web.api.model.AuthErrorResponse;
import org.example.web.api.model.CommandRequest;
import org.example.web.api.model.CommandResponse;
import org.example.web.api.model.WebSocketCommandRequest;
import org.example.web.api.model.WebSocketCommandResponse;
import org.example.web.api.model.WebSocketErrorResponse;
import org.example.web.api.model.WebSocketLogResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.javalin.apibuilder.ApiBuilder.beforeMatched;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.ws;

public class WebServer {
    private Javalin server;
    private final Cache<String, Integer> rateLimitCache = CacheBuilder.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build();
    private final Set<WebSocketClient> webSocketClients = ConcurrentHashMap.newKeySet();
    private final CircularLogQueue circularLogQueue = new CircularLogQueue(
        Math.max(1, RaspiPlugin.PLUGIN_CONFIG.webApi.logRetentionEntries),
        this::broadcastLogs
    );

    public synchronized void start() {
        if (server != null) {
            stop();
        }
        try {
            server = createServer();
            server.start(RaspiPlugin.PLUGIN_CONFIG.webApi.port);
            RaspiPlugin.LOG.info("Web API started on port {}", RaspiPlugin.PLUGIN_CONFIG.webApi.port);
            RaspiPlugin.LOG.info("Auth token: {}", RaspiPlugin.PLUGIN_CONFIG.webApi.authToken);
            RaspiPlugin.LOG.info("ZenithProxyWebAPI is running on http://localhost:{}", RaspiPlugin.PLUGIN_CONFIG.webApi.port);
        } catch (final Exception e) {
            server = null;
            RaspiPlugin.LOG.error("Web API failed to start on port {}: {}", RaspiPlugin.PLUGIN_CONFIG.webApi.port, e.getMessage());
            RaspiPlugin.LOG.warn("Web API is not available. Pick a free port with '/webApi port <port>' then enable with '/webApi on'");
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
            webSocketClients.clear();
            RaspiPlugin.LOG.info("Web API stopped");
        }
    }

    public synchronized boolean isRunning() {
        return server != null && server.jettyServer().started();
    }

    private Javalin createServer() {
        return Javalin.create(config -> {
            var threadPool = new ExecutorThreadPool();
            threadPool.setDaemon(true);
            threadPool.setName("ZenithProxy-WebAPI-%d");
            config.jetty.threadPool = threadPool;
            config.http.defaultContentType = "application/json";
            var objectMapper = JavalinJackson3.defaultMapper();
            config.jsonMapper(new JavalinJackson3(objectMapper, false));
            config.staticFiles.add("/web", Location.CLASSPATH);
            config.routes.wsBeforeUpgrade("/api/ws", this::authorizeWebSocketUpgrade);
            config.routes.apiBuilder(() -> {
                beforeMatched(ctx -> {
                    if (ctx.path().equals("/command") || ctx.path().equals("/api/command")) {
                        authorizeHttpRequest(ctx);
                    } else if (!RaspiPlugin.PLUGIN_CONFIG.webApi.webUI) {
                        ctx.status(404);
                        ctx.skipRemainingHandlers();
                    }
                });
                Handler commandHandler = ctx -> {
                    var request = ctx.bodyAsClass(CommandRequest.class);
                    var command = request.command();
                    var commandContext = CommandContext.create(command, WebAPICommandSource.INSTANCE);
                    RaspiPlugin.LOG.info("{} executed command: {}", ctx.ip(), command);
                    Globals.COMMAND.execute(commandContext);
                    commandContext.getSource().logEmbed(commandContext, commandContext.getEmbed());
                    String embedResponse = null;
                    String embedResponseComponent = null;
                    var multiLineResponse = commandContext.getMultiLineOutput();
                    if (commandContext.getEmbed().isTitlePresent()) {
                        var embedComponent = EmbedSerializer.serialize(commandContext.getEmbed());
                        embedResponse = ComponentSerializer.serializePlain(embedComponent);
                        embedResponseComponent = ComponentSerializer.serializeJson(embedComponent);
                    }
                    ctx.json(new CommandResponse(embedResponse, embedResponseComponent, multiLineResponse));
                    ctx.status(200);
                };
                post("/command", commandHandler);
                post("/api/command", commandHandler);
                ws("/api/ws", ws -> {
                    ws.onConnect(ctx -> {
                        ctx.session.setIdleTimeout(Duration.ZERO);
                        var client = new WebSocketClient(ctx);
                        webSocketClients.add(client);
                        client.sendInitialSnapshot();
                    });
                    ws.onMessage(this::handleWebSocketMessage);
                    ws.onClose(ctx -> webSocketClients.remove(new WebSocketClient(ctx)));
                    ws.onError(ctx -> {
                        webSocketClients.remove(new WebSocketClient(ctx));
                        RaspiPlugin.LOG.debug("WebSocket error for {}", ctx.sessionId(), ctx.error());
                    });
                });
            });
        });
    }

    private void authorizeHttpRequest(final Context ctx) throws Exception {
        var ip = ctx.ip();
        if (RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimiter) {
            synchronized (rateLimitCache) {
                var requestCount = rateLimitCache.get(ip, () -> 0);
                rateLimitCache.put(ip, requestCount + 1);
                if (requestCount >= RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimitRequestsPerMinute) {
                    ctx.status(429).json(new AuthErrorResponse("Rate limit exceeded"));
                    ctx.skipRemainingHandlers();
                    RaspiPlugin.LOG.warn("Rate limit exceeded for IP: {}", ip);
                    return;
                }
            }
        }

        var authToken = ctx.header("Authorization");
        if (RaspiPlugin.PLUGIN_CONFIG.webApi.authToken.equals(authToken)) {
            rateLimitCache.invalidate(ip);
            return;
        }

        var reason = authToken == null ? "Authorization header missing" : "Invalid auth token";
        ctx.status(401).json(new AuthErrorResponse(reason));
        ctx.skipRemainingHandlers();
        RaspiPlugin.LOG.warn("Denied request from {}: {}", ip, reason);
    }

    private void authorizeWebSocketUpgrade(final Context ctx) throws Exception {
        var ip = ctx.ip();
        if (RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimiter) {
            synchronized (rateLimitCache) {
                var requestCount = rateLimitCache.get(ip, () -> 0);
                rateLimitCache.put(ip, requestCount + 1);
                if (requestCount >= RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimitRequestsPerMinute) {
                    ctx.status(429).json(new AuthErrorResponse("Rate limit exceeded"));
                    ctx.skipRemainingHandlers();
                    RaspiPlugin.LOG.warn("Rate limit exceeded for IP: {}", ip);
                    return;
                }
            }
        }

        var authToken = ctx.header("Authorization");
        if (authToken == null) {
            authToken = ctx.queryParam("token");
        }
        if (RaspiPlugin.PLUGIN_CONFIG.webApi.authToken.equals(authToken)) {
            rateLimitCache.invalidate(ip);
            ctx.attribute("webSocketIp", ip);
            return;
        }

        var reason = authToken == null ? "Auth token missing" : "Invalid auth token";
        ctx.status(401).json(new AuthErrorResponse(reason));
        ctx.skipRemainingHandlers();
        RaspiPlugin.LOG.warn("Denied WebSocket connection from {}: {}", ip, reason);
    }

    private void handleWebSocketMessage(final WsMessageContext ctx) {
        WebSocketCommandRequest request;
        try {
            request = ctx.messageAsClass(WebSocketCommandRequest.class);
        } catch (Exception e) {
            ctx.send(new WebSocketErrorResponse(null, "Invalid JSON message"));
            return;
        }

        if (!"command".equals(request.type())) {
            ctx.send(new WebSocketErrorResponse(request.requestId(), "Unsupported message type"));
            return;
        }
        var command = request.command() == null ? "" : request.command().trim();
        if (command.isEmpty()) {
            ctx.send(new WebSocketErrorResponse(request.requestId(), "Command must not be empty"));
            return;
        }

        try {
            var commandContext = CommandContext.create(command, WebAPICommandSource.INSTANCE);
            RaspiPlugin.LOG.info("{} executed command: {}", ctx.<String>attribute("webSocketIp"), command);
            Globals.COMMAND.execute(commandContext);
            commandContext.getSource().logEmbed(commandContext, commandContext.getEmbed());
            String embedResponse = null;
            String embedResponseComponent = null;
            var multiLineResponse = commandContext.getMultiLineOutput();
            if (commandContext.getEmbed().isTitlePresent()) {
                var embedComponent = EmbedSerializer.serialize(commandContext.getEmbed());
                embedResponse = ComponentSerializer.serializePlain(embedComponent);
                embedResponseComponent = ComponentSerializer.serializeJson(embedComponent);
            }
            ctx.send(new WebSocketCommandResponse(
                request.requestId(),
                embedResponse,
                embedResponseComponent,
                multiLineResponse
            ));
        } catch (Exception e) {
            RaspiPlugin.LOG.warn("WebSocket command failed: {}", command, e);
            ctx.send(new WebSocketErrorResponse(request.requestId(), "Command failed: " + e.getMessage()));
        }
    }

    private void broadcastLogs() {
        for (var client : webSocketClients) {
            client.sendAvailableLogs();
        }
    }

    private WebSocketLogResponse createLogResponse(final String type, final CircularLogQueue.LogSnapshot snapshot) {
        var lines = new ArrayList<String>(snapshot.events().size());
        for (var event : snapshot.events()) {
            lines.add(event.ansi());
        }
        return new WebSocketLogResponse(
            type,
            snapshot.baseIndex(),
            snapshot.fromIndex(),
            snapshot.nextIndex(),
            snapshot.retained(),
            lines
        );
    }

    private final class WebSocketClient {
        private final WsContext context;
        private long nextLogIndex;
        private boolean initialized;

        private WebSocketClient(final WsContext context) {
            this.context = context;
        }

        private synchronized void sendInitialSnapshot() {
            if (initialized) {
                return;
            }
            var snapshot = circularLogQueue.snapshot(0, Integer.MAX_VALUE);
            context.send(createLogResponse("snapshot", snapshot));
            nextLogIndex = snapshot.nextIndex();
            initialized = true;
        }

        private synchronized void sendAvailableLogs() {
            if (!initialized) {
                return;
            }
            var snapshot = circularLogQueue.snapshot(nextLogIndex, Integer.MAX_VALUE);
            if (snapshot.nextIndex() == nextLogIndex && snapshot.baseIndex() <= nextLogIndex) {
                return;
            }
            context.send(createLogResponse("logs", snapshot));
            nextLogIndex = snapshot.nextIndex();
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof WebSocketClient other && context.equals(other.context);
        }

        @Override
        public int hashCode() {
            return context.hashCode();
        }
    }
}