package com.rheinmetal.tianshu.client.presence.capture;

import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;

import java.util.List;

/**
 * Publishes the low-frequency client events presence owns, and nothing else.
 *
 * <p>It is deliberately not a capture path: game context is read when a query is answered, so there is no
 * per-input or per-screen state to keep warm here. Draining events after the world session ended is
 * suppressed rather than published into the next world.</p>
 */
public final class PresenceEventCollector {
    private PresenceWorldEventSink worldEventSink = PresenceWorldEventSink.NOOP;
    private volatile boolean worldSessionActive;

    public void setWorldEventSink(PresenceWorldEventSink worldEventSink) {
        this.worldEventSink = worldEventSink == null ? PresenceWorldEventSink.NOOP : worldEventSink;
    }

    /**
     * One chat line, published as a world event with a speaker.
     *
     * <p>Chat used to have its own payload and its own topic; it is an event kind now, so consumers still
     * switch on one topic and one payload shape.</p>
     */
    public void recordPlayerChatMessage(String messageText, String senderId, String senderName) {
        if (!worldSessionActive) {
            return;
        }
        if (messageText == null || messageText.isBlank() || senderId == null || senderId.isBlank()) {
            return;
        }
        worldEventSink.publish(PresenceWorldEventPayload.chatMessage(
                senderId,
                cleanSenderName(senderName),
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

    public void startWorldSession() {
        worldSessionActive = true;
    }

    public void stopWorldSession() {
        worldSessionActive = false;
    }

    private String cleanSenderName(String senderName) {
        return senderName == null ? "" : senderName.trim();
    }
}
