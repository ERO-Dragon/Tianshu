package com.rheinmetal.tianshu.function.chatassistant;

import com.rheinmetal.tianshu.core.FeatureManager;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantClientEventPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantIncomingChatPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantInterruptPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantSendPayload;
import com.rheinmetal.tianshu.protocol.payload.VoiceTriggerPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.concurrent.atomic.AtomicLong;

public final class ChatAssistantModule implements TianshuManagedModule {
    private static final long OPEN_INPUT_MS = 5_000L;
    private static final int MAX_TEXT_LENGTH = 256;

    private final ChatAssistantProtocolAdapter adapter;
    private final ChatAssistantBroadcastPolicy broadcastPolicy = new ChatAssistantBroadcastPolicy();
    private final AtomicLong sessionIds = new AtomicLong();

    private ChatAssistantState state = ChatAssistantState.IDLE;
    private ChatAssistantInputSession inputSession;
    private ProtocolTaskHandle timeoutTask;

    public ChatAssistantModule(ProtocolRuntime runtime) {
        this.adapter = new ChatAssistantProtocolAdapter(runtime);
    }

    @Override
    public String moduleId() {
        return "module.chat_assistant";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        adapter.registerVoiceWords();
        adapter.registerVoiceTriggerCapability(this::handleVoiceTrigger);
        adapter.registerInterruptCapability(this::handleInterrupt);
        adapter.registerIncomingChatCapability(this::handleIncomingChat);
    }

    public synchronized boolean isInputActive() {
        return state == ChatAssistantState.INPUT_OPEN && inputSession != null;
    }

    private synchronized void handleVoiceTrigger(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof VoiceTriggerPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "chat assistant voice trigger payload is invalid", null);
            return;
        }
        if (!FeatureManager.isChatAssistantEnabled()) {
            closeInput(context, "feature_disabled", true);
            return;
        }
        expireIfNeeded(context, System.currentTimeMillis());
        ChatAssistantCommand command = ChatAssistantCommand.parse(payload, isInputActive());
        switch (command.action()) {
            case OPEN -> openInput(context, "voice_open");
            case SEND -> sendAndClose(context);
            case CANCEL -> closeInput(context, "cancelled", true);
            case RETRY -> retryInput(context);
            case APPEND -> appendText(context, command.text());
            case IGNORE -> showHint(context, isInputActive() ? "未识别到可追加的聊天内容" : "请先说“聊天”打开语音聊天框");
        }
    }

    private synchronized void handleInterrupt(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof ChatAssistantInterruptPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "chat assistant interrupt payload is invalid", null);
            return;
        }
        if (isInputActive()) {
            closeInput(context, payload.reason().name().toLowerCase(), true);
            return;
        }
        if (payload.reason() == ChatAssistantInterruptPayload.Reason.PLAYER_DEATH) {
            context.submit(adapter.sendClientEvent(new ChatAssistantClientEventPayload(ChatAssistantClientEventPayload.Action.CLOSE_INPUT, "", 0L, "player_death")));
        }
    }

    private synchronized void handleIncomingChat(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof ChatAssistantIncomingChatPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "chat assistant incoming chat payload is invalid", null);
            return;
        }
        if (!FeatureManager.isChatAssistantEnabled()) {
            return;
        }
        ChatAssistantBroadcastPolicy.Decision decision = broadcastPolicy.decide(payload);
        if (decision.action() == ChatAssistantBroadcastPolicy.Decision.Action.IGNORE) {
            return;
        }
        submitBroadcast(context, decision);
    }

    private void submitBroadcast(ProtocolContext context, ChatAssistantBroadcastPolicy.Decision decision) {
        switch (decision.action()) {
            case SPEAK -> context.submit(adapter.speak(decision.text()));
            case ALERT_ONLY -> context.submit(adapter.speakInterrupting(decision.text()));
            case IGNORE -> {
            }
        }
    }

    private void openInput(ProtocolContext context, String reason) {
        cancelTimeoutTask();
        inputSession = new ChatAssistantInputSession(sessionIds.incrementAndGet(), System.currentTimeMillis() + OPEN_INPUT_MS, MAX_TEXT_LENGTH);
        state = ChatAssistantState.INPUT_OPEN;
        context.submit(adapter.sendClientEvent(new ChatAssistantClientEventPayload(ChatAssistantClientEventPayload.Action.OPEN_INPUT, "", inputSession.deadlineAtMillis(), reason)));
        scheduleTimeout(inputSession.sessionId(), inputSession.version(), inputSession.deadlineAtMillis());
    }

    private void sendAndClose(ProtocolContext context) {
        if (!isInputActive()) {
            showHint(context, "请先说“聊天”打开语音聊天框");
            return;
        }
        String text = inputSession.text().trim();
        long sessionId = inputSession.sessionId();
        if (!inputSession.hasText()) {
            showHint(context, "聊天内容为空，未发送");
            closeInput(context, "empty_send", true);
            return;
        }
        context.submit(adapter.sendChatMessage(new ChatAssistantSendPayload(text, sessionId)));
        closeInput(context, "sent", true);
    }

    private void closeInput(ProtocolContext context, String reason, boolean notifyClient) {
        cancelTimeoutTask();
        state = ChatAssistantState.IDLE;
        inputSession = null;
        if (notifyClient) {
            context.submit(adapter.sendClientEvent(new ChatAssistantClientEventPayload(ChatAssistantClientEventPayload.Action.CLOSE_INPUT, "", 0L, reason)));
        }
    }

    private void retryInput(ProtocolContext context) {
        if (!isInputActive()) {
            showHint(context, "请先说“聊天”打开语音聊天框");
            return;
        }
        cancelTimeoutTask();
        inputSession.clear();
        inputSession.resetDeadline(System.currentTimeMillis() + OPEN_INPUT_MS);
        context.submit(adapter.sendClientEvent(new ChatAssistantClientEventPayload(ChatAssistantClientEventPayload.Action.RESET_COUNTDOWN, "", inputSession.deadlineAtMillis(), "retry")));
        scheduleTimeout(inputSession.sessionId(), inputSession.version(), inputSession.deadlineAtMillis());
    }

    private void appendText(ProtocolContext context, String text) {
        if (!isInputActive()) {
            showHint(context, "请先说“聊天”打开语音聊天框");
            return;
        }
        ChatAssistantInputSession.AppendResult result = inputSession.appendText(text);
        if (result == ChatAssistantInputSession.AppendResult.EMPTY) {
            return;
        }
        inputSession.resetDeadline(System.currentTimeMillis() + OPEN_INPUT_MS);
        context.submit(adapter.sendClientEvent(new ChatAssistantClientEventPayload(ChatAssistantClientEventPayload.Action.UPDATE_TEXT, inputSession.text(), inputSession.deadlineAtMillis(), result.name().toLowerCase())));
        if (result == ChatAssistantInputSession.AppendResult.TRUNCATED || result == ChatAssistantInputSession.AppendResult.FULL) {
            showHint(context, "聊天内容已达到长度上限");
        }
        cancelTimeoutTask();
        scheduleTimeout(inputSession.sessionId(), inputSession.version(), inputSession.deadlineAtMillis());
    }

    private void expireIfNeeded(ProtocolContext context, long nowMillis) {
        if (isInputActive() && inputSession.isExpired(nowMillis)) {
            closeInput(context, "timeout", true);
        }
    }

    private void scheduleTimeout(long sessionId, long version, long deadlineAtMillis) {
        long delayMillis = Math.max(0L, deadlineAtMillis - System.currentTimeMillis() + 100L);
        timeoutTask = adapter.scheduleTimeout(() -> handleTimeout(sessionId, version), delayMillis);
    }

    private synchronized void handleTimeout(long sessionId, long version) {
        if (!isInputActive()) {
            return;
        }
        if (inputSession.sessionId() != sessionId || inputSession.version() != version || !inputSession.isExpired(System.currentTimeMillis())) {
            return;
        }
        cancelTimeoutTask();
        state = ChatAssistantState.IDLE;
        inputSession = null;
        adapter.sendClientEvent(new ChatAssistantClientEventPayload(ChatAssistantClientEventPayload.Action.CLOSE_INPUT, "", 0L, "timeout"));
    }

    private void cancelTimeoutTask() {
        if (timeoutTask != null && !timeoutTask.isDone()) {
            timeoutTask.cancel("chat assistant session changed");
        }
        timeoutTask = null;
    }

    private void showHint(ProtocolContext context, String text) {
        context.submit(adapter.sendClientEvent(new ChatAssistantClientEventPayload(ChatAssistantClientEventPayload.Action.SHOW_HINT, text, 0L, "hint")));
    }
}
