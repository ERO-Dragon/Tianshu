package com.rheinmetal.tianshu.client.presence.capture;

import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;

@FunctionalInterface
public interface PresenceChatMessageSink {
    PresenceChatMessageSink NOOP = payload -> {
    };

    void publish(PresenceChatMessagePayload payload);
}
