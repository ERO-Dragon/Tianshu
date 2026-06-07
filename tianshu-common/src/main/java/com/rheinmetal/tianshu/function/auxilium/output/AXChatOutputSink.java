package com.rheinmetal.tianshu.function.auxilium.output;

public interface AXChatOutputSink {
    AXChatOutputSink NOOP = new AXChatOutputSink() {
    };

    default void begin(AXOutputContext context) {
    }

    default void append(AXOutputContext context, String text) {
    }

    default void complete(AXOutputContext context, String fullText) {
    }

    default void fail(AXOutputContext context, String reason) {
    }
}
