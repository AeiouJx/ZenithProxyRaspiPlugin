package org.example.qq;

import com.google.gson.JsonObject;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientConfigurationEvent;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.event.client.ClientDeathMessageEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientLoginFailedEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.client.ClientStartConnectEvent;
import com.zenith.event.client.MsaDeviceCodeLoginEvent;
import com.zenith.event.client.PrioStatusUpdateEvent;
import com.zenith.event.module.ActiveHoursConnectEvent;
import com.zenith.event.module.AutoEatOutOfFoodEvent;
import com.zenith.event.module.AutoReconnectEvent;
import com.zenith.event.module.HealthAutoDisconnectEvent;
import com.zenith.event.module.NoTotemsEvent;
import com.zenith.event.module.PlayerHealthChangedEvent;
import com.zenith.event.module.PlayerTotemPopAlertEvent;
import com.zenith.event.module.QueueWarningEvent;
import com.zenith.event.module.ReplayStartedEvent;
import com.zenith.event.module.ReplayStoppedEvent;
import com.zenith.event.module.ServerPlayerAttackedUsEvent;
import com.zenith.event.module.ServerPlayerInVisualRangeEvent;
import com.zenith.event.module.ServerPlayerLeftVisualRangeEvent;
import com.zenith.event.module.ServerPlayerLogoutInVisualRangeEvent;
import com.zenith.event.module.SessionTimeLimitWarningEvent;
import com.zenith.event.module.SpawnPatrolTargetAcquiredEvent;
import com.zenith.event.module.SpawnPatrolTargetKilledEvent;
import com.zenith.event.module.TasksCommandExecutedEvent;
import com.zenith.event.player.BlacklistedPlayerConnectedEvent;
import com.zenith.event.player.NonWhitelistedPlayerConnectedEvent;
import com.zenith.event.player.PlayerConnectedEvent;
import com.zenith.event.player.PlayerDisconnectedEvent;
import com.zenith.event.player.SpectatorConnectedEvent;
import com.zenith.event.player.SpectatorDisconnectedEvent;
import com.zenith.event.plugin.PluginLoadFailureEvent;
import com.zenith.event.plugin.PluginLoadedEvent;
import com.zenith.event.queue.QueueCompleteEvent;
import com.zenith.event.queue.QueuePositionUpdateEvent;
import com.zenith.event.queue.QueueSkipEvent;
import com.zenith.event.queue.QueueStartEvent;
import com.zenith.event.server.ServerRestartingEvent;
import com.zenith.event.update.UpdateAvailableEvent;
import com.zenith.event.update.UpdateStartEvent;

public class QQNotificationEventListener {
    private final QQBot qqBot;

    private int lastQueuePosition = -1;
    private long lastQueuePushMillis;

    public QQNotificationEventListener(final QQBot qqBot) {
        this.qqBot = qqBot;
    }

    // **************** bot status ****************

    public void handlePlayerOnlineEvent(final ClientOnlineEvent event) {
        qqBot.debugEvent("ClientOnlineEvent", "bot is online");
        qqBot.sendBotStatus("online");
    }

    public void handleDisconnectEvent(final ClientDisconnectEvent event) {
        qqBot.debugEvent("ClientDisconnectEvent", "reason=" + event.reason() + " manual=" + event.manualDisconnect()
            + " online=" + event.onlineDuration().toSeconds() + "s queue=" + event.queuePosition() + " inQueue=" + event.wasInQueue());
        qqBot.sendBotStatus("offline");
        var data = new JsonObject();
        data.addProperty("reason", event.reason());
        data.addProperty("manual", event.manualDisconnect());
        data.addProperty("online_duration_seconds", event.onlineDuration().toSeconds());
        data.addProperty("was_in_queue", event.wasInQueue());
        data.addProperty("queue_position", event.queuePosition());
        qqBot.sendEvent("disconnect", data);
    }

    public void handleDeathEvent(final ClientDeathEvent event) {
        qqBot.debugEvent("ClientDeathEvent", "bot died");
        qqBot.sendBotStatus("dead");
        var data = new JsonObject();
        var position = qqBot.currentBotPosition();
        if (position != null) data.add("position", position);
        qqBot.sendEvent("death", data);
    }

    // **************** security ****************

    public void handleServerPlayerAttackedUsEvent(final ServerPlayerAttackedUsEvent event) {
        qqBot.debugEvent("ServerPlayerAttackedUsEvent", "attacker=" + qqBot.playerDisplayName(event.attacker()));
        var attacker = event.attacker();
        var data = new JsonObject();
        data.addProperty("player", qqBot.playerDisplayName(attacker));
        var pos = event.sourcePosition() != null ? event.sourcePosition() : attacker.position();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        qqBot.sendEvent("attack", data);
    }

    public void handleTotemPopEvent(final PlayerTotemPopAlertEvent event) {
        qqBot.debugEvent("PlayerTotemPopAlertEvent", "totems_remaining=" + event.totemsRemaining());
        var data = new JsonObject();
        data.addProperty("totems_remaining", event.totemsRemaining());
        qqBot.sendEvent("totem_pop", data);
    }

    public void handleNoTotemsEvent(final NoTotemsEvent event) {
        qqBot.debugEvent("NoTotemsEvent", "no totems");
        qqBot.sendEvent("totem_empty", new JsonObject());
    }

    // **************** visual range ****************

    public void handleVisualRangeEnterEvent(final ServerPlayerInVisualRangeEvent event) {
        qqBot.debugEvent("ServerPlayerInVisualRangeEvent", qqBot.entryName(event.playerEntry()));
        qqBot.sendVisualEvent("visual_enter", event.playerEntry(), event.playerEntity());
    }

    public void handleVisualRangeLeaveEvent(final ServerPlayerLeftVisualRangeEvent event) {
        qqBot.debugEvent("ServerPlayerLeftVisualRangeEvent", qqBot.entryName(event.playerEntry()));
        qqBot.sendVisualEvent("visual_leave", event.playerEntry(), event.playerEntity());
    }

    public void handleVisualRangeLogoutEvent(final ServerPlayerLogoutInVisualRangeEvent event) {
        qqBot.debugEvent("ServerPlayerLogoutInVisualRangeEvent", qqBot.entryName(event.playerEntry()));
        qqBot.sendVisualEvent("visual_logout", event.playerEntry(), event.playerEntity());
    }

    // **************** denied connections ****************

    public void handleBlacklistedPlayerConnectedEvent(final BlacklistedPlayerConnectedEvent event) {
        handleDeniedConnection(event.gameProfile().getName(), event.remoteAddress().toString(), "blacklisted");
    }

    public void handleNonWhitelistedPlayerConnectedEvent(final NonWhitelistedPlayerConnectedEvent event) {
        handleDeniedConnection(event.gameProfile().getName(), event.remoteAddress().toString(), "not_whitelisted");
    }

    private void handleDeniedConnection(final String player, final String ip, final String reason) {
        qqBot.debugEvent("DeniedConnection(" + reason + ")", player + " " + ip);
        var data = new JsonObject();
        data.addProperty("player", player);
        data.addProperty("ip", ip);
        data.addProperty("reason", reason);
        qqBot.sendEvent("connection_denied", data);
    }

    // **************** proxy control connections (Discord: Client/Spectator Connected/Disconnected) ****************

    public void handleProxyClientConnectedEvent(final PlayerConnectedEvent event) {
        final String name = event.clientGameProfile().getName();
        qqBot.debugEvent("PlayerConnectedEvent", name + " mc=" + mcVersion(event.session()));
        var data = new JsonObject();
        data.addProperty("player", name);
        data.addProperty("mc_version", mcVersion(event.session()));
        qqBot.sendEvent("client_player_connected", data);
    }

    public void handleProxyClientDisconnectedEvent(final PlayerDisconnectedEvent event) {
        final var profile = event.clientGameProfile();
        qqBot.debugEvent("PlayerDisconnectedEvent", profile != null ? profile.getName() : "?");
        var data = new JsonObject();
        if (profile != null) data.addProperty("player", profile.getName());
        if (event.reason() != null) data.addProperty("reason", event.reason());
        qqBot.sendEvent("client_player_disconnected", data);
    }

    public void handleProxySpectatorConnectedEvent(final SpectatorConnectedEvent event) {
        qqBot.debugEvent("SpectatorConnectedEvent", event.clientGameProfile().getName() + " mc=" + mcVersion(event.session()));
        var data = new JsonObject();
        data.addProperty("player", event.clientGameProfile().getName());
        data.addProperty("mc_version", mcVersion(event.session()));
        qqBot.sendEvent("spectator_connected", data);
    }

    public void handleProxySpectatorDisconnectedEvent(final SpectatorDisconnectedEvent event) {
        qqBot.debugEvent("SpectatorDisconnectedEvent", event.clientGameProfile().getName());
        var data = new JsonObject();
        data.addProperty("player", event.clientGameProfile().getName());
        qqBot.sendEvent("spectator_disconnected", data);
    }

    private static String mcVersion(final Object sessionHolder) {
        try {
            final String v = ((com.zenith.network.server.ServerSession) sessionHolder).getMCVersion();
            return v != null ? v : "";
        } catch (final Exception e) {
            return "";
        }
    }

    // **************** queue ****************

    public void handleQueuePositionUpdateEvent(final QueuePositionUpdateEvent event) {
        qqBot.debugEvent("QueuePositionUpdateEvent", "position=" + event.position());
        long now = System.currentTimeMillis();
        if (event.position() == lastQueuePosition) return;
        if (now - lastQueuePushMillis < 30_000) return;
        lastQueuePosition = event.position();
        lastQueuePushMillis = now;
        var data = new JsonObject();
        data.addProperty("position", event.position());
        qqBot.sendEvent("queue_position", data);
    }

    public void handleQueueCompleteEvent(final QueueCompleteEvent event) {
        qqBot.debugEvent("QueueCompleteEvent", "duration=" + event.queueDuration().toSeconds() + "s");
        var data = new JsonObject();
        data.addProperty("duration_seconds", event.queueDuration().toSeconds());
        qqBot.sendEvent("queue_complete", data);
    }

    public void handleStartQueueEvent(final QueueStartEvent event) {
        qqBot.debugEvent("QueueStartEvent", "was_online=" + event.wasOnline() + " online_duration=" + event.wasOnlineDuration().toSeconds() + "s");
        var data = new JsonObject();
        data.addProperty("was_online", event.wasOnline());
        data.addProperty("online_duration_seconds", event.wasOnlineDuration().toSeconds());
        qqBot.sendEvent("queue_start", data);
    }

    public void handleQueueSkipEvent(final QueueSkipEvent event) {
        qqBot.debugEvent("QueueSkipEvent", "queue skipped");
        qqBot.sendEvent("queue_skip", new JsonObject());
    }

    public void handleQueueWarning(final QueueWarningEvent event) {
        qqBot.debugEvent("QueueWarningEvent", "position=" + event.position() + " mention=" + event.mention());
        var data = new JsonObject();
        data.addProperty("position", event.position());
        data.addProperty("mention", event.mention());
        qqBot.sendEvent("queue_warning", data);
    }

    // **************** connection ****************

    public void handleStartConnectEvent(final ClientStartConnectEvent event) {
        qqBot.debugEvent("ClientStartConnectEvent", "connecting to server");
        qqBot.sendEvent("client_connecting", new JsonObject());
    }

    public void handleConnectEvent(final ClientConnectEvent event) {
        qqBot.debugEvent("ClientConnectEvent", "connected to server");
        qqBot.sendEvent("client_connected", new JsonObject());
    }

    public void handleProxyLoginFailedEvent(final ClientLoginFailedEvent event) {
        qqBot.debugEvent("ClientLoginFailedEvent", String.valueOf(event.exception()));
        var data = new JsonObject();
        data.addProperty("error", event.exception() != null ? event.exception().getMessage() : "unknown");
        qqBot.sendEvent("client_login_failed", data);
    }

    public void handleClientConfigurationEnteringEvent(final ClientConfigurationEvent.Entering event) {
        qqBot.debugEvent("ClientConfigurationEvent.Entering", "reconfiguring");
        qqBot.sendEvent("client_reconfiguring", new JsonObject());
    }

    // **************** session / auto ****************

    public void handleSessionTimeLimitEvent(final SessionTimeLimitWarningEvent event) {
        qqBot.debugEvent("SessionTimeLimitWarningEvent", "kick in " + event.durationUntilKick().toMinutes() + "m");
        var data = new JsonObject();
        data.addProperty("duration_until_kick_minutes", event.durationUntilKick().toMinutes());
        data.addProperty("session_time_limit_hours", event.sessionTimeLimit().toHoursPart());
        qqBot.sendEvent("session_time_limit_warning", data);
    }

    public void handleAutoReconnectEvent(final AutoReconnectEvent event) {
        qqBot.debugEvent("AutoReconnectEvent", "delay=" + event.delaySeconds() + "s");
        var data = new JsonObject();
        data.addProperty("delay_seconds", event.delaySeconds());
        qqBot.sendEvent("auto_reconnect", data);
    }

    public void handleAutoEatOutOfFoodEvent(final AutoEatOutOfFoodEvent event) {
        qqBot.debugEvent("AutoEatOutOfFoodEvent", "no food");
        qqBot.sendEvent("auto_eat_out_of_food", new JsonObject());
    }

    // **************** death / server ****************

    public void handleSelfDeathMessageEvent(final ClientDeathMessageEvent event) {
        qqBot.debugEvent("ClientDeathMessageEvent", event.message());
        var data = new JsonObject();
        data.addProperty("message", event.message());
        qqBot.sendEvent("bot_death_message", data);
    }

    public void handleServerRestartingEvent(final ServerRestartingEvent event) {
        qqBot.debugEvent("ServerRestartingEvent", event.message());
        var data = new JsonObject();
        data.addProperty("message", event.message());
        qqBot.sendEvent("server_restarting", data);
    }

    // **************** prio / update ****************

    public void handlePrioStatusUpdateEvent(final PrioStatusUpdateEvent event) {
        qqBot.debugEvent("PrioStatusUpdateEvent", "prio=" + event.prio());
        var data = new JsonObject();
        data.addProperty("prio", event.prio());
        qqBot.sendEvent("prio_status", data);
    }

    public void handleUpdateAvailableEvent(final UpdateAvailableEvent event) {
        qqBot.debugEvent("UpdateAvailableEvent", "version=" + event.getVersion().orElse("?"));
        var data = new JsonObject();
        data.addProperty("version", event.getVersion().orElse(null));
        qqBot.sendEvent("update_available", data);
    }

    public void handleUpdateStartEvent(final UpdateStartEvent event) {
        qqBot.debugEvent("UpdateStartEvent", "version=" + event.newVersion().orElse("?"));
        var data = new JsonObject();
        data.addProperty("version", event.newVersion().orElse(null));
        qqBot.sendEvent("update_start", data);
    }

    // **************** replay ****************

    public void handleReplayStartedEvent(final ReplayStartedEvent event) {
        qqBot.debugEvent("ReplayStartedEvent", "recording started");
        qqBot.sendEvent("replay_started", new JsonObject());
    }

    public void handleReplayStoppedEvent(final ReplayStoppedEvent event) {
        qqBot.debugEvent("ReplayStoppedEvent", event.replayFile() != null ? event.replayFile().getName() : "");
        var data = new JsonObject();
        data.addProperty("file", event.replayFile() != null ? event.replayFile().getName() : null);
        qqBot.sendEvent("replay_stopped", data);
    }

    // **************** active hours / spawn patrol ****************

    public void handleActiveHoursConnectEvent(final ActiveHoursConnectEvent event) {
        qqBot.debugEvent("ActiveHoursConnectEvent", "will_wait=" + event.willWait());
        var data = new JsonObject();
        data.addProperty("will_wait", event.willWait());
        qqBot.sendEvent("active_hours_connect", data);
    }

    public void handleSpawnPatrolTargetAcquiredEvent(final SpawnPatrolTargetAcquiredEvent event) {
        qqBot.debugEvent("SpawnPatrolTargetAcquiredEvent", event.targetProfile().getName());
        var data = new JsonObject();
        data.addProperty("player", event.targetProfile().getName());
        var pos = event.target().blockPos();
        if (pos != null) {
            data.addProperty("x", pos.x());
            data.addProperty("y", pos.y());
            data.addProperty("z", pos.z());
        }
        qqBot.sendEvent("spawn_patrol_target", data);
    }

    public void handleSpawnPatrolTargetKilledEvent(final SpawnPatrolTargetKilledEvent event) {
        qqBot.debugEvent("SpawnPatrolTargetKilledEvent", event.message());
        var data = new JsonObject();
        data.addProperty("player", event.profile().getName());
        data.addProperty("message", event.message());
        qqBot.sendEvent("spawn_patrol_target_killed", data);
    }

    // **************** misc ****************

    public void handleScheduledTaskCommandExecutedEvent(final TasksCommandExecutedEvent event) {
        qqBot.debugEvent("TasksCommandExecutedEvent", event.command());
        var data = new JsonObject();
        data.addProperty("command", event.command());
        qqBot.sendEvent("tasks_command", data);
    }

    public void handlePluginLoadFailure(final PluginLoadFailureEvent event) {
        qqBot.debugEvent("PluginLoadFailureEvent", event.message());
        var data = new JsonObject();
        data.addProperty("id", event.id());
        data.addProperty("jar", event.jarPath().getFileName().toString());
        data.addProperty("message", event.message());
        qqBot.sendEvent("plugin_load_failure", data);
    }

    public void handlePluginLoadedEvent(final PluginLoadedEvent event) {
        qqBot.debugEvent("PluginLoadedEvent", event.pluginInfo().id());
        var data = new JsonObject();
        final var info = event.pluginInfo();
        data.addProperty("id", info.id());
        data.addProperty("description", info.description());
        data.addProperty("version", info.version().toString());
        data.addProperty("url", info.url());
        data.addProperty("authors", String.join(", ", info.authors()));
        qqBot.sendEvent("plugin_loaded", data);
    }

    public void handleMsaDeviceCodeLoginEvent(final MsaDeviceCodeLoginEvent event) {
        final String uri = event.deviceCode().getDirectVerificationUri();
        final String code = event.deviceCode().getUserCode();
        qqBot.debugEvent("MsaDeviceCodeLoginEvent", uri + " code=" + code);
        var data = new JsonObject();
        data.addProperty("url", uri);
        data.addProperty("code", code);
        qqBot.sendEvent("msa_device_code", data);
    }

    public void handlePlayerHealthChangedEvent(final PlayerHealthChangedEvent event) {
        qqBot.debugEvent("PlayerHealthChangedEvent", event.previousHealth() + " -> " + event.newHealth());
        if (event.newHealth() < event.previousHealth() && event.newHealth() < 8) {
            var data = new JsonObject();
            data.addProperty("health", event.newHealth());
            qqBot.sendEvent("health_warning", data);
        }
    }

    public void handleHealthAutoDisconnectEvent(final HealthAutoDisconnectEvent event) {
        qqBot.debugEvent("HealthAutoDisconnectEvent", "health too low");
        qqBot.sendEvent("health_autodisconnect", new JsonObject());
    }

    // **************** scan ****************

    public void handleScanTick(final ClientBotTick event) {
        qqBot.handleScanTick();
    }
}
