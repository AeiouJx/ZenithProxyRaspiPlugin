package org.example.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import com.zenith.util.RandomString;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CACHE;
import static org.example.RaspiPlugin.PLUGIN_CONFIG;

public class PromoterModule extends Module {
    private final Timer tickTimer = Timers.tickTimer();
    private int spamIndex = 0;
    private final HashSet<String> whisperedPlayers = new HashSet<>();

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.promoter.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleClientTick),
            of(ClientBotTick.Starting.class, this::handleClientTickStarting)
        );
    }

    @Override
    public void onEnable() {
        whisperedPlayers.clear();
    }

    @Override
    public void onDisable() {
        whisperedPlayers.clear();
    }

    private void handleClientTick(final ClientBotTick event) {
        var proxy = Proxy.getInstance();
        if (proxy.isInQueue()) return;
        if (!proxy.isOnlineForAtLeastDuration(Duration.ofSeconds(5))) return;
        if (!PLUGIN_CONFIG.promoter.whilePlayerConnected && proxy.hasActivePlayer()) return;
        if (tickTimer.tick(PLUGIN_CONFIG.promoter.delayTicks)) {
            sendSpam();
        }
    }

    private void handleClientTickStarting(final ClientBotTick.Starting event) {
        tickTimer.reset();
        spamIndex = 0;
    }

    private void sendSpam() {
        if (PLUGIN_CONFIG.promoter.messages.isEmpty()) return;
        if (PLUGIN_CONFIG.promoter.randomOrder) {
            spamIndex = ThreadLocalRandom.current().nextInt(0, PLUGIN_CONFIG.promoter.messages.size());
        } else {
            spamIndex = (spamIndex + 1) % PLUGIN_CONFIG.promoter.messages.size();
        }
        var msg = PLUGIN_CONFIG.promoter.messages.get(spamIndex)
            + (PLUGIN_CONFIG.promoter.appendRandom ? " [" + RandomString.generate(6) + "]" : "");
        if (PLUGIN_CONFIG.promoter.whisper) {
            var player = getNextPlayer();
            if (player != null) {
                var packet = ChatUtil.getWhisperChatPacket(player, msg);
                debug("> {}", packet.getMessage());
                sendClientPacketAsync(packet);
            }
        } else {
            var packet = new ServerboundChatPacket(ChatUtil.sanitizeChatMessage(msg));
            debug("> {}", packet.getMessage());
            sendClientPacketAsync(packet);
        }
    }

    private String getNextPlayer() {
        var nextPlayer = CACHE.getTabListCache().getEntries().stream()
            .map(PlayerListEntry::getName)
            .filter(name -> !name.equals(com.zenith.Globals.CONFIG.authentication.username))
            .filter(name -> !this.whisperedPlayers.contains(name))
            .findFirst();
        if (nextPlayer.isPresent()) {
            this.whisperedPlayers.add(nextPlayer.get());
            return nextPlayer.get();
        } else {
            if (this.whisperedPlayers.isEmpty()) return null;
            this.whisperedPlayers.clear();
            return getNextPlayer();
        }
    }
}