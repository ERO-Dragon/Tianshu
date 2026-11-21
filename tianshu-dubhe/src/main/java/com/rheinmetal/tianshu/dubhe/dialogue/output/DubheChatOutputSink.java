package com.rheinmetal.tianshu.dubhe.dialogue.output;

public interface DubheChatOutputSink {
    DubheChatOutputSink NOOP = new DubheChatOutputSink() {
    };

    default void begin(DubheOutputContext context) {
    }

    default void append(DubheOutputContext context, String text) {
    }

    default void complete(DubheOutputContext context, String fullText) {
    }

    default void fail(DubheOutputContext context, String reason) {
    }
}
