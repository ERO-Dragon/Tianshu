package com.rheinmetal.tianshu.presence.capture;

import com.rheinmetal.tianshu.presence.payload.PresenceWorldEventPayload;

@FunctionalInterface
public interface PresenceWorldEventSink {
    PresenceWorldEventSink NOOP = payload -> {
    };

    void publish(PresenceWorldEventPayload payload);
}
