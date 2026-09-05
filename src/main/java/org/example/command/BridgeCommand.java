package org.example.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import org.example.module.BridgeModule;

import java.time.Duration;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
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
                "sharedToken <token>",
                "serverId <id>"
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
                    .addField("Server ID", PLUGIN_CONFIG().bridge.serverId)
                    .addField("Heartbeat Interval", PLUGIN_CONFIG().bridge.heartbeatIntervalSeconds)
                    .addField("Last Heartbeat", bridge.getLastHeartbeatMs() < 0
                        ? "Never"
                        : Duration.ofMillis(System.currentTimeMillis() - bridge.getLastHeartbeatMs()).toSeconds() + "s ago")
                    .addField("Last Error", bridge.getLastError() == null ? "None" : bridge.getLastError())
                    .primaryColor();
            }))
            .then(literal("reconnect").executes(c -> {
                MODULE.get(BridgeModule.class).reconnect();
                c.getSource().getEmbed()
                    .title("Reconnecting...");
            }))
            .then(literal("wsUrl")
                .then(argument("url", string()).executes(c -> {
                    PLUGIN_CONFIG().bridge.wsUrl = getString(c, "url");
                    c.getSource().getEmbed()
                        .title("WS URL Updated!")
                        .description(PLUGIN_CONFIG().bridge.wsUrl);
                })))
            .then(literal("sharedToken")
                .then(argument("token", string()).executes(c -> {
                    PLUGIN_CONFIG().bridge.sharedToken = getString(c, "token");
                    c.getSource().getEmbed()
                        .title("Shared Token Updated!");
                })))
            .then(literal("serverId")
                .then(argument("id", string()).executes(c -> {
                    PLUGIN_CONFIG().bridge.serverId = getString(c, "id");
                    c.getSource().getEmbed()
                        .title("Server ID Updated!")
                        .description(PLUGIN_CONFIG().bridge.serverId);
                })));
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