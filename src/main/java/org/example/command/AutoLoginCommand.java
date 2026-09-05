package org.example.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import org.example.module.AutoLoginModule;

import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static org.example.RaspiPlugin.PLUGIN_CONFIG;

public class AutoLoginCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("autoLogin")
            .category(CommandCategory.MODULE)
            .description("""
                Auto-login for 3c3u.org (offline auth only).

                Automatically sends /login with password and answers /captcha prompts.
                Auto-reconnect is handled by the built-in AutoReconnect module.
                """)
            .usageLines("on/off")
            .aliases("autologin")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoLogin")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.autoLogin.enabled = getToggle(c, "toggle");
                MODULE.get(AutoLoginModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("AutoLogin " + toggleStrCaps(PLUGIN_CONFIG.autoLogin.enabled));
            }));
    }

    @Override
    public void defaultEmbed(final com.zenith.discord.Embed builder) {
        builder.description("""
                 **AutoLogin** for 3c3u.org (offline auth only).
                 Auto /login and /captcha handling.
                 """)
            .addField("AutoLogin", toggleStr(PLUGIN_CONFIG.autoLogin.enabled))
            .primaryColor();
    }
}