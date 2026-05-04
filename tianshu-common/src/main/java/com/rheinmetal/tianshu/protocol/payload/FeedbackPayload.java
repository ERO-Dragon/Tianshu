package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.Priority;

public record FeedbackPayload(String text, Priority priority, boolean interrupt, String channel, String source) implements ITianshuPayload {
    public FeedbackPayload {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text cannot be blank");
        }
        priority = priority == null ? Priority.NORMAL : priority;
        channel = channel == null || channel.isBlank() ? "tts" : channel;
        source = source == null || source.isBlank() ? "feedback.flow" : source;
    }
}
