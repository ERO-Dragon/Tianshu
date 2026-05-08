package com.rheinmetal.tianshu.client.chatassistant;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantClientEventPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantIncomingChatPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantInterruptPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantSendPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.locale.Language;
import org.slf4j.Logger;

public final class ChatAssistantClientBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ChatAssistantClientProtocolAdapter adapter;
    private final ChatAssistantClientState state = new ChatAssistantClientState();

    public ChatAssistantClientBridge(ProtocolRuntime runtime) {
        this.adapter = new ChatAssistantClientProtocolAdapter(runtime);
    }

    public void register() {
        adapter.registerClientEventCapability(this::handleClientEvent);
        adapter.registerSendCapability(this::handleSend);
    }

    public ChatAssistantClientState state() {
        return state;
    }

    public void tick() {
        state.tick();
    }

    public void close() {
        state.close();
    }

    public void forceInterrupt(ChatAssistantInterruptPayload.Reason reason, String detail) {
        state.close();
        adapter.sendInterrupt(new ChatAssistantInterruptPayload(reason, detail, System.currentTimeMillis()));
    }

    public void publishIncomingChat(String senderName, String messageText, String localPlayerName, boolean mentionsSelf) {
        if (messageText == null || messageText.isBlank()) {
            return;
        }
        String languageCode = Language.getInstance().getOrDefault("language.code");
        adapter.sendIncomingChat(new ChatAssistantIncomingChatPayload(senderName, messageText, localPlayerName, languageCode, mentionsSelf, System.currentTimeMillis()));
    }

    private void handleClientEvent(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof ChatAssistantClientEventPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "chat assistant client event payload is invalid", null);
            return;
        }
        switch (payload.action()) {
            case OPEN_INPUT -> state.open(payload.deadlineAtMillis(), payload.reason());
            case UPDATE_TEXT -> state.updateText(payload.text(), payload.deadlineAtMillis(), payload.reason());
            case RESET_COUNTDOWN -> state.resetCountdown(payload.text(), payload.deadlineAtMillis(), payload.reason());
            case CLOSE_INPUT -> state.close();
            case SHOW_HINT -> state.showHint(payload.text());
        }
    }

    private void handleSend(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof ChatAssistantSendPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "chat assistant send payload is invalid", null);
            return;
        }
        sendChat(payload.text());
    }

    private void sendChat(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        if (player == null || connection == null) {
            LOGGER.warn("通语发送聊天失败：玩家或连接不存在");
            return;
        }
        String normalized = text.trim();
        if (normalized.startsWith("/")) {
            connection.sendCommand(normalized.substring(1));
        } else {
            connection.sendChat(normalized);
        }
    }
}
