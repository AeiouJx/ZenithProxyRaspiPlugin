package org.example.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import org.example.module.BridgeModule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

public class BridgeCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("bridge")
            .category(CommandCategory.MODULE)
            .description("""
                WebSocket bridge to AstrBot.

                Maintains a persistent WebSocket connection to the AstrBot server,
                sends heartbeat messages, and auto-reconnects on disconnect.
                """)
            .usageLines(
                "on/off",
                "status",
                "reconnect",
                "wsUrl <url>",
                "token <token>",
                "serverId <id>",
                "debug on/off",
                "scan on/off",
                "scan status",
                "scan radius <n>",
                "scan targets"
            )
            .aliases("ws")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("bridge")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG().bridge.enabled = getToggle(c, "toggle");
                MODULE.get(BridgeModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Bridge " + toggleStrCaps(PLUGIN_CONFIG().bridge.enabled));
            }))
            .then(literal("status").executes(c -> {
                var bridge = MODULE.get(BridgeModule.class);
                c.getSource().getEmbed()
                    .title("Bridge Status")
                    .description(PLUGIN_CONFIG().bridge.wsUrl)
                    .addField("Connected", toggleStr(bridge.isBridgeConnected()))
                    .addField("Handshake", bridge.isHandshakeComplete() ? "Complete" : "Pending")
                    .addField("Server ID", bridge.getEffectiveServerId())
                    .addField("Account", bridge.getCurrentBotName())
                    .addField("HWID", bridge.getHwid())
                    .addField("Server Name", PLUGIN_CONFIG().bridge.serverName)
                    .addField("Heartbeat Interval", PLUGIN_CONFIG().bridge.heartbeatIntervalSeconds)
                    .addField("Last Heartbeat", bridge.getLastHeartbeatMs() < 0
                        ? "Never"
                        : Duration.ofMillis(System.currentTimeMillis() - bridge.getLastHeartbeatMs()).toSeconds() + "s ago")
                    .addField("Last Ack", bridge.getLastHeartbeatAckMs() < 0
                        ? "Never"
                        : Duration.ofMillis(System.currentTimeMillis() - bridge.getLastHeartbeatAckMs()).toSeconds() + "s ago")
                    .addField("Last Error", bridge.getLastError() == null ? "None" : bridge.getLastError())
                    .primaryColor();
            }))
            .then(literal("reconnect").executes(c -> {
                MODULE.get(BridgeModule.class).reconnect();
                c.getSource().getEmbed()
                    .title("Reconnecting...");
            }))
            .then(literal("wsUrl")
                .then(argument("url", greedyString()).executes(c -> {
                    PLUGIN_CONFIG().bridge.wsUrl = getString(c, "url");
                    c.getSource().getEmbed()
                        .title("WS URL Updated!")
                        .description(PLUGIN_CONFIG().bridge.wsUrl);
                })))
            .then(literal("token")
                .then(argument("token", greedyString()).executes(c -> {
                    PLUGIN_CONFIG().bridge.token = getString(c, "token");
                    c.getSource().getEmbed()
                        .title("Token Updated!");
                })))
            .then(literal("serverId")
                .then(argument("id", greedyString()).executes(c -> {
                    PLUGIN_CONFIG().bridge.serverId = getString(c, "id");
                    MODULE.get(BridgeModule.class).sendUpdateInfo();
                    c.getSource().getEmbed()
                        .title("Server ID Updated!")
                        .description(PLUGIN_CONFIG().bridge.serverId);
                })))
            .then(literal("debug")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG().bridge.eventDebugLogging = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Event Debug Logging " + toggleStrCaps(PLUGIN_CONFIG().bridge.eventDebugLogging));
                })))
            .then(literal("scan")
                .then(literal("status").executes(c -> {
                    var scan = PLUGIN_CONFIG().bridge.scan;
                    c.getSource().getEmbed()
                        .title("Scan Status")
                        .description(toggleStr(scan.enabled))
                        .addField("Radius", scan.radius)
                        .addField("Interval", scan.intervalSeconds + "s")
                        .addField("Targets", String.join(", ", scan.targets))
                        .primaryColor();
                }))
                .then(literal("on").executes(c -> {
                    PLUGIN_CONFIG().bridge.scan.enabled = true;
                    c.getSource().getEmbed()
                        .title("Scan Enabled!");
                }))
                .then(literal("off").executes(c -> {
                    PLUGIN_CONFIG().bridge.scan.enabled = false;
                    c.getSource().getEmbed()
                        .title("Scan Disabled!");
                }))
                .then(literal("radius")
                    .then(argument("radius", integer(1, 64)).executes(c -> {
                        PLUGIN_CONFIG().bridge.scan.radius = getInteger(c, "radius");
                        c.getSource().getEmbed()
                            .title("Scan Radius Updated!")
                            .description(String.valueOf(PLUGIN_CONFIG().bridge.scan.radius));
                    })))
                .then(literal("targets")
                    .then(argument("list", greedyString()).executes(c -> {
                        String list = getString(c, "list");
                        List<String> parsed = new ArrayList<>();
                        for (final String part : list.split("[,\\s]+")) {
                            if (!part.isEmpty()) parsed.add(part.toLowerCase(Locale.ROOT));
                        }
                        PLUGIN_CONFIG().bridge.scan.targets.clear();
                        PLUGIN_CONFIG().bridge.scan.targets.addAll(parsed);
                        c.getSource().getEmbed()
                            .title("Scan Targets Updated!")
                            .description(String.join(", ", PLUGIN_CONFIG().bridge.scan.targets));
                    }))));
    }

    @Override
    public void defaultEmbed(final com.zenith.discord.Embed builder) {
        var bridge = MODULE.get(BridgeModule.class);
        builder.description("""
                 **Bridge** - WebSocket bridge to AstrBot.
                 """)
            .addField("Bridge", toggleStr(PLUGIN_CONFIG().bridge.enabled))
            .addField("Connected", toggleStr(bridge.isBridgeConnected()))
            .addField("WS URL", PLUGIN_CONFIG().bridge.wsUrl)
            .addField("Server ID", PLUGIN_CONFIG().bridge.serverId)
            .addField("Heartbeat Interval", PLUGIN_CONFIG().bridge.heartbeatIntervalSeconds)
            .primaryColor();
    }

    private static org.example.RaspiConfig PLUGIN_CONFIG() {
        return org.example.RaspiPlugin.PLUGIN_CONFIG;
    }
}