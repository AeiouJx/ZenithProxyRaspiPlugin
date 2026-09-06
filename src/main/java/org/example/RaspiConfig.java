package org.example;

/**
 * Example configuration POJO.
 *
 * Configurations are saved and loaded to JSON files.
 *
 * Save and load is handled automatically, happens on every command execution, proxy start/stop, etc.
 *
 * All fields should be public and mutable.
 *
 * Fields to static inner classes generate nested JSON objects.
 */
public class RaspiConfig {
    public boolean esp = false;

    public final ExampleModuleConfig exampleModule = new ExampleModuleConfig();
    public static class ExampleModuleConfig {
        public boolean enabled = true;
        public int delayTicks = 250;
    }

    public final ExampleWanderConfig wander = new ExampleWanderConfig();
    public static final class ExampleWanderConfig {
        public boolean enabled = false;
        public int radius = 2000;
        public int minRadius = 100;
    }

    public final AutoLoginConfig autoLogin = new AutoLoginConfig();
    public static class AutoLoginConfig {
        public boolean enabled = false;
    }

    public final WebApiConfig webApi = new WebApiConfig();
    public static class WebApiConfig {
        public boolean enabled = true;
        public int port = 8080;
        public String authToken = java.util.UUID.randomUUID().toString();
        public boolean webUI = true;
        public int logRetentionEntries = 150;
        public boolean rateLimiter = true;
        public int rateLimitRequestsPerMinute = 30;
        public boolean commandsAccountOwnerPerms = false;
    }

    public final BridgeConfig bridge = new BridgeConfig();
    public static class BridgeConfig {
        public boolean enabled = false;
        public String wsUrl = "ws://127.0.0.1:8765/ws";
        public String sharedToken = "change-me";
        public String serverId = "default";
        public int heartbeatIntervalSeconds = 5;
        public int reconnectIntervalSeconds = 5;
    }

    public final PromoterConfig promoter = new PromoterConfig();
    public static class PromoterConfig {
        public boolean enabled = false;
        public boolean whilePlayerConnected = false;
        public boolean whisper = false;
        public long delayTicks = 200;
        public boolean randomOrder = false;
        public boolean appendRandom = false;
        public final java.util.ArrayList<String> messages = com.google.common.collect.Lists.newArrayList(
            "ZenithProxy on top!",
            "I just skipped queue thanks to ZenithProxy!",
            "Download ZenithProxy on GitHub today! It's free!"
        );
    }
}
