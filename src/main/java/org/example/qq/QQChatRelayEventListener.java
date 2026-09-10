package org.example.qq;

import com.google.gson.JsonObject;
import com.zenith.event.chat.DeathMessageChatEvent;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.message.PrivateMessageSendEvent;
import com.zenith.event.server.ServerPlayerConnectedEvent;
import com.zenith.event.server.ServerPlayerDisconnectedEvent;
import org.example.util.ChatMessageParser;

public class QQChatRelayEventListener {
    private final QQBot qqBot;

    public QQChatRelayEventListener(final QQBot qqBot) {
        this.qqBot = qqBot;
    }

    public void handlePublicChatEvent(final PublicChatEvent event) {
        qqBot.debugEvent("PublicChatEvent", event.message());
        if (qqBot.isOwnMessage(event.sender().getName(), event.message())) return;
        var data = new JsonObject();
        data.addProperty("sender", event.sender().getName());
        data.addProperty("message", event.message());
        qqBot.sendEvent("chat", data);
    }

    public void handleWhisperChatEvent(final WhisperChatEvent event) {
        qqBot.debugEvent("WhisperChatEvent", "outgoing=" + event.outgoing() + " sender=" + event.sender().getName() + " receiver=" + event.receiver().getName() + " " + event.message());
        var data = new JsonObject();
        data.addProperty("outgoing", event.outgoing());
        data.addProperty("sender", event.sender().getName());
        data.addProperty("receiver", event.receiver().getName());
        data.addProperty("message", event.message());
        qqBot.sendEvent("whisper", data);
    }

    public void handlePrivateMessageSendEvent(final PrivateMessageSendEvent event) {
        qqBot.debugEvent("PrivateMessageSendEvent", event.getSenderName() + " -> " + event.getStringContents());
        var data = new JsonObject();
        data.addProperty("outgoing", false);
        data.addProperty("sender", event.getSenderName());
        data.addProperty("receiver", qqBot.currentBotName());
        data.addProperty("message", event.getStringContents());
        qqBot.sendEvent("whisper", data);
    }

    public void handleSystemChatEvent(final SystemChatEvent event) {
        final String message = event.message();
        qqBot.debugEvent("SystemChatEvent", message);
        final var priv = ChatMessageParser.parsePrivate(message);
        if (priv.isPresent()) {
            final String sender = priv.get().sender();
            final String botName = qqBot.currentBotName();
            final boolean outgoing = "You".equals(sender) || sender.equals(botName);
            String receiver = priv.get().receiver();
            if (receiver.isEmpty()) {
                receiver = outgoing ? "" : botName;
            }
            var data = new JsonObject();
            data.addProperty("outgoing", outgoing);
            data.addProperty("sender", sender);
            data.addProperty("receiver", receiver);
            data.addProperty("message", priv.get().message());
            qqBot.sendEvent("whisper", data);
            return;
        }
        final var pub = ChatMessageParser.parsePublic(message);
        if (pub.isPresent()) {
            var data = new JsonObject();
            data.addProperty("sender", pub.get().sender());
            data.addProperty("message", pub.get().message());
            qqBot.sendEvent("chat", data);
            return;
        }
        final var ach = ChatMessageParser.parseAchievement(message);
        if (ach.isPresent()) {
            var data = new JsonObject();
            data.addProperty("player", ach.get().player());
            data.addProperty("achievement", ach.get().achievement());
            qqBot.sendEvent("achievement", data);
            return;
        }
        var data = new JsonObject();
        data.addProperty("message", message);
        qqBot.sendEvent("system", data);
    }

    public void handleServerPlayerConnectedEvent(final ServerPlayerConnectedEvent event) {
        qqBot.debugEvent("ServerPlayerConnectedEvent", event.playerEntry().getName());
        var data = new JsonObject();
        data.addProperty("player", event.playerEntry().getName());
        data.addProperty("message", event.playerEntry().getName() + " joined the game");
        qqBot.sendEvent("player_join", data);
    }

    public void handleServerPlayerDisconnectedEvent(final ServerPlayerDisconnectedEvent event) {
        qqBot.debugEvent("ServerPlayerDisconnectedEvent", event.playerEntry().getName());
        var data = new JsonObject();
        data.addProperty("player", event.playerEntry().getName());
        data.addProperty("message", event.playerEntry().getName() + " left the game");
        qqBot.sendEvent("player_leave", data);
    }

    public void handleDeathMessageChatEvent(final DeathMessageChatEvent event) {
        qqBot.debugEvent("DeathMessageChatEvent", event.deathMessage().toString());
        var dm = event.deathMessage();
        var data = new JsonObject();
        data.addProperty("victim", dm.victim());
        dm.killer().ifPresent(k -> data.addProperty("killer", k.name()));
        dm.weapon().ifPresent(w -> data.addProperty("weapon", w));
        qqBot.sendEvent("player_death", data);
    }
}
