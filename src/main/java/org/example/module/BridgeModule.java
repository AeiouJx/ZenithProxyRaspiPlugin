package org.example.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.chat.DeathMessageChatEvent;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
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
import com.zenith.event.message.PrivateMessageSendEvent;
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
import com.zenith.event.server.ServerPlayerConnectedEvent;
import com.zenith.event.server.ServerPlayerDisconnectedEvent;
import com.zenith.event.server.ServerRestartingEvent;
import com.zenith.event.update.UpdateAvailableEvent;
import com.zenith.event.update.UpdateStartEvent;
import com.zenith.module.api.Module;
import org.example.qq.QQBot;
import org.example.qq.QQChatRelayEventListener;
import org.example.qq.QQNotificationEventListener;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static org.example.RaspiPlugin.PLUGIN_CONFIG;

public class BridgeModule extends Module {
    private final QQBot qqBot = new QQBot();
    private final QQChatRelayEventListener chatRelay = new QQChatRelayEventListener(qqBot);
    private final QQNotificationEventListener notification = new QQNotificationEventListener(qqBot);

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.bridge.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            // **************** chat relay (mirrors Discord ChatRelayEventListener) ****************
            of(WhisperChatEvent.class, chatRelay::handleWhisperChatEvent),
            of(SystemChatEvent.class, chatRelay::handleSystemChatEvent),
            of(PublicChatEvent.class, chatRelay::handlePublicChatEvent),
            of(PrivateMessageSendEvent.class, chatRelay::handlePrivateMessageSendEvent),
            of(DeathMessageChatEvent.class, chatRelay::handleDeathMessageChatEvent),
            of(ServerPlayerConnectedEvent.class, chatRelay::handleServerPlayerConnectedEvent),
            of(ServerPlayerDisconnectedEvent.class, chatRelay::handleServerPlayerDisconnectedEvent),
            // **************** notifications (mirrors Discord NotificationEventListener) ****************
            // bot connect / online / disconnect
            of(ClientConnectEvent.class, notification::handleConnectEvent),
            of(ClientOnlineEvent.class, notification::handlePlayerOnlineEvent),
            of(ClientConfigurationEvent.Entering.class, notification::handleClientConfigurationEnteringEvent),
            of(ClientDisconnectEvent.class, notification::handleDisconnectEvent),
            // queue
            of(QueuePositionUpdateEvent.class, notification::handleQueuePositionUpdateEvent),
            of(QueueWarningEvent.class, notification::handleQueueWarning),
            of(AutoEatOutOfFoodEvent.class, notification::handleAutoEatOutOfFoodEvent),
            of(QueueCompleteEvent.class, notification::handleQueueCompleteEvent),
            of(QueueStartEvent.class, notification::handleStartQueueEvent),
            of(QueueSkipEvent.class, notification::handleQueueSkipEvent),
            // bot自身死亡
            of(ClientDeathEvent.class, notification::handleDeathEvent),
            of(ClientDeathMessageEvent.class, notification::handleSelfDeathMessageEvent),
            of(HealthAutoDisconnectEvent.class, notification::handleHealthAutoDisconnectEvent),
            // proxy 控制连接
            of(PlayerConnectedEvent.class, notification::handleProxyClientConnectedEvent),
            of(SpectatorConnectedEvent.class, notification::handleProxySpectatorConnectedEvent),
            of(PlayerDisconnectedEvent.class, notification::handleProxyClientDisconnectedEvent),
            // 视野内玩家
            of(ServerPlayerInVisualRangeEvent.class, notification::handleVisualRangeEnterEvent),
            of(ServerPlayerLeftVisualRangeEvent.class, notification::handleVisualRangeLeaveEvent),
            of(ServerPlayerLogoutInVisualRangeEvent.class, notification::handleVisualRangeLogoutEvent),
            // 被拒连接
            of(NonWhitelistedPlayerConnectedEvent.class, notification::handleNonWhitelistedPlayerConnectedEvent),
            of(BlacklistedPlayerConnectedEvent.class, notification::handleBlacklistedPlayerConnectedEvent),
            of(SpectatorDisconnectedEvent.class, notification::handleProxySpectatorDisconnectedEvent),
            of(ActiveHoursConnectEvent.class, notification::handleActiveHoursConnectEvent),
            // 我们遭受攻击
            of(ServerPlayerAttackedUsEvent.class, notification::handleServerPlayerAttackedUsEvent),
            // 更新 / 服务器重启
            of(UpdateStartEvent.class, notification::handleUpdateStartEvent),
            of(ServerRestartingEvent.class, notification::handleServerRestartingEvent),
            // 登录失败 / 开始连接 / prio / 重连
            of(ClientLoginFailedEvent.class, notification::handleProxyLoginFailedEvent),
            of(ClientStartConnectEvent.class, notification::handleStartConnectEvent),
            of(PrioStatusUpdateEvent.class, notification::handlePrioStatusUpdateEvent),
            of(AutoReconnectEvent.class, notification::handleAutoReconnectEvent),
            of(MsaDeviceCodeLoginEvent.class, notification::handleMsaDeviceCodeLoginEvent),
            of(UpdateAvailableEvent.class, notification::handleUpdateAvailableEvent),
            // replay
            of(ReplayStartedEvent.class, notification::handleReplayStartedEvent),
            of(ReplayStoppedEvent.class, notification::handleReplayStoppedEvent),
            // totem
            of(PlayerTotemPopAlertEvent.class, notification::handleTotemPopEvent),
            of(NoTotemsEvent.class, notification::handleNoTotemsEvent),
            // plugin
            of(PluginLoadFailureEvent.class, notification::handlePluginLoadFailure),
            of(PluginLoadedEvent.class, notification::handlePluginLoadedEvent),
            // spawn patrol
            of(SpawnPatrolTargetAcquiredEvent.class, notification::handleSpawnPatrolTargetAcquiredEvent),
            of(SpawnPatrolTargetKilledEvent.class, notification::handleSpawnPatrolTargetKilledEvent),
            // session / tasks / misc
            of(SessionTimeLimitWarningEvent.class, notification::handleSessionTimeLimitEvent),
            of(TasksCommandExecutedEvent.class, notification::handleScheduledTaskCommandExecutedEvent),
            of(PlayerHealthChangedEvent.class, notification::handlePlayerHealthChangedEvent),
            of(ClientBotTick.class, notification::handleScanTick)
        );
    }

    @Override
    public void onEnable() {
        qqBot.connect();
    }

    @Override
    public void onDisable() {
        qqBot.cleanup();
    }

    // **************** delegated getters for BridgeCommand ****************

    public QQBot getQqBot() {
        return qqBot;
    }

    public boolean isBridgeConnected() {
        return qqBot.isBridgeConnected();
    }

    public long getLastHeartbeatMs() {
        return qqBot.getLastHeartbeatMs();
    }

    public long getLastHeartbeatAckMs() {
        return qqBot.getLastHeartbeatAckMs();
    }

    public boolean isHandshakeComplete() {
        return qqBot.isHandshakeComplete();
    }

    public String getLastError() {
        return qqBot.getLastError();
    }

    public String getEffectiveServerId() {
        return qqBot.getEffectiveServerId();
    }

    public String getCurrentBotName() {
        return qqBot.getCurrentBotName();
    }

    public String getHwid() {
        return qqBot.getHwid();
    }

    public void connect() {
        qqBot.connect();
    }

    public void disconnect() {
        qqBot.disconnect();
    }

    public void reconnect() {
        qqBot.reconnect();
    }

    public void sendUpdateInfo() {
        qqBot.sendUpdateInfo();
    }
}
