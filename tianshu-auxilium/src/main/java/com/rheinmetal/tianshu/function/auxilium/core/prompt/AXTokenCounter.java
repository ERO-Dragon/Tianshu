package com.rheinmetal.tianshu.function.auxilium.core.prompt;

public interface AXTokenCounter {
    AXTokenCounter UNAVAILABLE = (requestId, role, content) -> java.util.OptionalInt.empty();

    java.util.OptionalInt countMessageTokens(String requestId, String role, String content);

    static AXTokenCounter unavailable() {
        return UNAVAILABLE;
    }
}
