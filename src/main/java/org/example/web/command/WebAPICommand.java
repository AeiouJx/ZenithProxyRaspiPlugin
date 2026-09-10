package org.example.web.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import org.example.RaspiPlugin;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

public class WebAPICommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("webApi")
            .category(CommandCategory.MODULE)
            .description("""
                Manages the HTTP web API for interacting with this ZenithProxy instance.
                """)
            .usageLines(
                "on/off",
                "port <port>",
                "auth <token>",
                "webUI on/off",
                "logRetentionEntries <entries>",
                "commandsAccountOwnerPerms on/off",
                "rateLimiter on/off",
                "rateLimiter requestsPerMinute <requestCount>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("webApi").requires(Command::validateAccountOwner)
            .then(argument("toggle", toggle()).executes(c -> {
                RaspiPlugin.PLUGIN_CONFIG.webApi.enabled = getToggle(c, "toggle");
                if (RaspiPlugin.PLUGIN_CONFIG.webApi.enabled) {
                    RaspiPlugin.WEB_SERVER.start();
                } else {
                    RaspiPlugin.WEB_SERVER.stop();
                }
                c.getSource().getEmbed()
                    .title("Web API " + toggleStrCaps(RaspiPlugin.PLUGIN_CONFIG.webApi.enabled));
            }))
            .then(literal("port").then(argument("portArg", integer(1, 65535)).executes(c -> {
                RaspiPlugin.PLUGIN_CONFIG.webApi.port = getInteger(c, "portArg");
                if (RaspiPlugin.PLUGIN_CONFIG.webApi.enabled) {
                    RaspiPlugin.WEB_SERVER.start();
                }
                c.getSource().getEmbed()
                    .title("Port Set");
            })))
            .then(literal("auth").then(argument("token", wordWithChars()).executes(c -> {
                RaspiPlugin.PLUGIN_CONFIG.webApi.authToken = getString(c, "token");
                c.getSource().getEmbed()
                    .title("Auth Token Set");
            })))
            .then(literal("webUI").then(argument("toggle", toggle()).executes(c -> {
                RaspiPlugin.PLUGIN_CONFIG.webApi.webUI = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Web UI " + toggleStrCaps(RaspiPlugin.PLUGIN_CONFIG.webApi.webUI));
            })))
            .then(literal("logRetentionEntries").then(argument("entries", integer(1)).executes(c -> {
                RaspiPlugin.PLUGIN_CONFIG.webApi.logRetentionEntries = getInteger(c, "entries");
                c.getSource().getEmbed()
                    .title("Log Retention Entries Set")
                    .addField("Info", "Changes will take effect on next ZenithProxy restart");
            })))
            .then(literal("commandsAccountOwnerPerms").then(argument("toggle", toggle()).executes(c -> {
                RaspiPlugin.PLUGIN_CONFIG.webApi.commandsAccountOwnerPerms = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Commands Account Owner Perms " + toggleStrCaps(RaspiPlugin.PLUGIN_CONFIG.webApi.commandsAccountOwnerPerms));
            })))
            .then(literal("rateLimiter")
                .then(argument("toggle", toggle()).executes(c -> {
                    RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimiter = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Rate Limiter " + toggleStrCaps(RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimiter));
                }))
                .then(literal("requestsPerMinute").then(argument("requests", integer(1)).executes(c -> {
                    RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimitRequestsPerMinute = getInteger(c, "requests");
                    c.getSource().getEmbed()
                        .title("Rate Limiter Requests Per Minute Set");
                }))));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .addField("Web API", RaspiPlugin.WEB_SERVER.isRunning() ? "Running" : "Stopped")
            .addField("Port", RaspiPlugin.PLUGIN_CONFIG.webApi.port)
            .addField("Auth Token", RaspiPlugin.PLUGIN_CONFIG.webApi.authToken)
            .addField("Web UI", toggleStr(RaspiPlugin.PLUGIN_CONFIG.webApi.webUI))
            .addField("Log Retention Entries", RaspiPlugin.PLUGIN_CONFIG.webApi.logRetentionEntries)
            .addField("Commands Account Owner Perms", RaspiPlugin.PLUGIN_CONFIG.webApi.commandsAccountOwnerPerms)
            .addField("Rate Limiter", toggleStr(RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimiter))
            .addField("Rate Limit Requests Per Minute", RaspiPlugin.PLUGIN_CONFIG.webApi.rateLimitRequestsPerMinute)
            .primaryColor();
    }
}