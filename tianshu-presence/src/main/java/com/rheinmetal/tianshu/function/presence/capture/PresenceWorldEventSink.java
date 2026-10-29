package com.rheinmetal.tianshu.function.presence.capture;

import com.rheinmetal.tianshu.function.presence.payload.PresenceWorldEventPayload;

@FunctionalInterface
public interface PresenceWorldEventSink {
    PresenceWorldEventSink NOOP = payload -> {
    };

    void publish(PresenceWorldEventPayload payload);
}
