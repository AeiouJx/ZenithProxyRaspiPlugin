package org.example.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import org.example.module.PromoterModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static org.example.ExamplePlugin.PLUGIN_CONFIG;

public class PromoterCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("promoter")
            .category(CommandCategory.MODULE)
            .description("""
                Promotes your server by sending messages or whispers in-game. Use with caution, this can and will get you muted.

                To add messages in bulk, use the `addAll` subcommand. Each message is delimited by the `,,` characters.
                """)
            .usageLines(
                "on/off",
                "whisper on/off",
                "whilePlayerConnected on/off",
                "delayTicks <int>",
                "randomOrder on/off",
                "appendRandom on/off",
                "list",
                "clear",
                "add <message>",
                "addAt <index> <message>",
                "addAll <message 1>,,<message 2>...",
                "del <index>"
            )
            .aliases("promo")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("promoter")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.promoter.enabled = getToggle(c, "toggle");
                MODULE.get(PromoterModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Promoter " + toggleStrCaps(PLUGIN_CONFIG.promoter.enabled));
            }))
            .then(literal("whisper")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.promoter.whisper = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Whisper " + toggleStrCaps(PLUGIN_CONFIG.promoter.whisper));
                })))
            .then(literal("whilePlayerConnected")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.promoter.whilePlayerConnected = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("While Player Connected " + toggleStrCaps(PLUGIN_CONFIG.promoter.whilePlayerConnected));
                })))
            .then(literal("delayTicks")
                .then(argument("delayTicks", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.promoter.delayTicks = IntegerArgumentType.getInteger(c, "delayTicks");
                    c.getSource().getEmbed()
                        .title("Delay Updated!");
                })))
            .then(literal("randomOrder")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.promoter.randomOrder = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Random Order " + toggleStrCaps(PLUGIN_CONFIG.promoter.randomOrder));
                })))
            .then(literal("appendRandom")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.promoter.appendRandom = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Append Random " + toggleStrCaps(PLUGIN_CONFIG.promoter.appendRandom));
                })))
            .then(literal("list").executes(c -> {
                c.getSource().getEmbed()
                    .title("Status");
            }))
            .then(literal("clear").executes(c -> {
                PLUGIN_CONFIG.promoter.messages.clear();
                c.getSource().getEmbed()
                    .title("Messages Cleared!");
            }))
            .then(literal("add")
                .then(argument("message", greedyString()).executes(c -> {
                    final String message = StringArgumentType.getString(c, "message");
                    PLUGIN_CONFIG.promoter.messages.add(message);
                    c.getSource().getEmbed()
                        .primaryColor()
                        .title("Message Added!");
                })))
            .then(literal("addAt").then(argument("index", integer(0)).then(argument("message", greedyString()).executes(c -> {
                final int index = IntegerArgumentType.getInteger(c, "index");
                final String message = StringArgumentType.getString(c, "message");
                try {
                    PLUGIN_CONFIG.promoter.messages.add(index, message);
                    c.getSource().getEmbed()
                        .title("Message Added!");
                    return OK;
                } catch (final Exception e) {
                    c.getSource().getEmbed()
                        .title("Invalid Index!");
                    return ERROR;
                }
            }))))
            .then(literal("addAll").then(argument("allMessages", greedyString()).executes(c -> {
                var input = getString(c, "allMessages");
                var split = input.split(",,");
                if (split.length == 0) {
                    c.getSource().getEmbed()
                        .title("Invalid Input")
                        .description("Each input message must be delimited by `,,`");
                    return ERROR;
                }
                PLUGIN_CONFIG.promoter.messages.addAll(Arrays.asList(split));
                c.getSource().getEmbed()
                    .title("All Message added!")
                    .addField("Added Message Count", split.length);
                return OK;
            })))
            .then(literal("del")
                .then(argument("index", integer(0)).executes(c -> {
                    final int index = IntegerArgumentType.getInteger(c, "index");
                    try {
                        PLUGIN_CONFIG.promoter.messages.remove(index);
                        addListDescription(c.getSource().getEmbed()
                            .title("Message Removed!"));
                        return OK;
                    } catch (final Exception e) {
                        c.getSource().getEmbed()
                            .title("Invalid Index!");
                        return ERROR;
                    }
                })));
    }

    @Override
    public void defaultEmbed(final Embed builder) {
        addListDescription(builder.description("""
                 **WARNING:** This module can and will get you muted on servers. Use at your own risk.
                 """))
            .addField("Promoter", toggleStr(PLUGIN_CONFIG.promoter.enabled))
            .addField("Whisper", toggleStr(PLUGIN_CONFIG.promoter.whisper))
            .addField("While Player Connected", toggleStr(PLUGIN_CONFIG.promoter.whilePlayerConnected))
            .addField("Delay", PLUGIN_CONFIG.promoter.delayTicks)
            .addField("Random Order", toggleStr(PLUGIN_CONFIG.promoter.randomOrder))
            .addField("Append Random", toggleStr(PLUGIN_CONFIG.promoter.appendRandom))
            .primaryColor();
    }

    private Embed addListDescription(final Embed embedBuilder) {
        final List<String> messages = new ArrayList<>();
        for (int index = 0; index < PLUGIN_CONFIG.promoter.messages.size(); index++) {
            messages.add("`" + index + ":` " + PLUGIN_CONFIG.promoter.messages.get(index));
        }
        String str = String.join("\n", messages);
        if (embedBuilder.isDescriptionPresent())
            return embedBuilder.description(embedBuilder.description() + str);
        else
            return embedBuilder.description(str);
    }
}