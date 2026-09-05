package org.example.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.util.List;
import java.util.regex.Pattern;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CONFIG;
import static com.zenith.util.config.Config.Authentication.AccountType.OFFLINE;
import static org.example.RaspiPlugin.LOG;
import static org.example.RaspiPlugin.PLUGIN_CONFIG;

public class AutoLoginModule extends Module {
    private final Pattern captchaPattern = Pattern.compile("^.*?/captcha\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoLogin.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(SystemChatEvent.class, this::handleSystemChat)
        );
    }

    private boolean isTargetServer() {
        return CONFIG.authentication.accountType == OFFLINE
            && "3c3u.org".equalsIgnoreCase(CONFIG.client.server.address);
    }

    private void handleSystemChat(final SystemChatEvent event) {
        if (!isTargetServer()) return;
        final String msg = event.message();
        if (msg == null || msg.isBlank()) return;
        handleLoginPrompt(msg);
        handleCaptcha(msg);
    }

    private void handleLoginPrompt(final String msg) {
        if (!msg.contains("/login")) return;
        final String password = CONFIG.authentication.password;
        LOG.info("[AutoLogin] Sending /login with password");
        sendClientPacketAsync(new ServerboundChatPacket("/login " + password));
    }

    private void handleCaptcha(final String msg) {
        final var matcher = captchaPattern.matcher(msg);
        if (!matcher.find()) return;
        final String captcha = matcher.group(1);
        LOG.info("[AutoLogin] Detected captcha, sending: {}", captcha);
        sendClientPacketAsync(new ServerboundChatPacket("/captcha " + captcha));
    }
}