package com.rheinmetal.tianshu.client.presence.capture;

import com.rheinmetal.tianshu.client.presence.PresenceStateStore;
import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInputKind;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PresenceEventCollector {
    private final PresenceStateStore stateStore;
    private final ClientGameContextProvider platform;
    private PresenceWorldEventSink worldEventSink = PresenceWorldEventSink.NOOP;
    private PresenceChatMessageSink chatMessageSink = PresenceChatMessageSink.NOOP;
    private long lastKeyboardEventAtMillis;
    private long lastMouseEventAtMillis;
    private volatile boolean worldSessionActive = true;

    public PresenceEventCollector(PresenceStateStore stateStore, ClientGameContextProvider platform) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public void setWorldEventSink(PresenceWorldEventSink worldEventSink) {
        this.worldEventSink = worldEventSink == null ? PresenceWorldEventSink.NOOP : worldEventSink;
    }

    public void setChatMessageSink(PresenceChatMessageSink chatMessageSink) {
        this.chatMessageSink = chatMessageSink == null ? PresenceChatMessageSink.NOOP : chatMessageSink;
    }

    public void recordScreenChanged() {
        if (!worldSessionActive) {
            return;
        }
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.NONE));
        stateStore.markDirty(PresenceContextGroup.PLAYER_INVENTORY);
    }

    public void recordKeyboardInput() {
        if (!worldSessionActive) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastKeyboardEventAtMillis < PresenceRefreshPolicy.INPUT_EVENT_MIN_INTERVAL_MILLIS) {
            return;
        }
        lastKeyboardEventAtMillis = now;
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.KEYBOARD));
    }

    public void recordMouseInput() {
        if (!worldSessionActive) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMouseEventAtMillis < PresenceRefreshPolicy.INPUT_EVENT_MIN_INTERVAL_MILLIS) {
            return;
        }
        lastMouseEventAtMillis = now;
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.MOUSE));
    }

    public void recordVoiceKeyInput() {
        if (!worldSessionActive) {
            return;
        }
        stateStore.updateContext(captureLiveSnapshot(PresenceInputKind.VOICE_KEY));
    }

    public void recordPlayerChatMessage(String messageText, String senderId, String senderName) {
        if (!worldSessionActive) {
            return;
        }
        if (messageText == null || messageText.isBlank() || senderId == null || senderId.isBlank()) {
            return;
        }
        String sender = cleanSenderName(senderName);
        chatMessageSink.publish(new PresenceChatMessagePayload(
                senderId,
                sender,
                messageText
        ));
    }

    public void recordWorldEvents(List<PresenceWorldEventPayload> events) {
        if (!worldSessionActive) {
            return;
        }
        if (events == null || events.isEmpty()) {
            return;
        }
        for (PresenceWorldEventPayload payload : events) {
            if (payload != null) {
                worldEventSink.publish(payload);
            }
        }
    }

    private PresenceContextSnapshot captureLiveSnapshot(PresenceInputKind inputKind) {
        return captureGroups(Set.of(PresenceContextGroup.INTERACTION_CONTEXT), inputKind);
    }

    public PresenceContextSnapshot captureGroups(Set<PresenceContextGroup> groups) {
        if (!worldSessionActive) {
            return PresenceContextSnapshot.empty();
        }
        return captureGroups(groups, PresenceInputKind.NONE);
    }

    public void startWorldSession() {
        worldSessionActive = true;
        lastKeyboardEventAtMillis = 0L;
        lastMouseEventAtMillis = 0L;
    }

    public void stopWorldSession() {
        worldSessionActive = false;
        lastKeyboardEventAtMillis = 0L;
        lastMouseEventAtMillis = 0L;
    }

    private PresenceContextSnapshot captureGroups(Set<PresenceContextGroup> groups, PresenceInputKind inputKind) {
        return platform.captureContext(groups, inputKind);
    }

    private String cleanSenderName(String senderName) {
        return senderName == null ? "" : senderName.trim();
    }

}
