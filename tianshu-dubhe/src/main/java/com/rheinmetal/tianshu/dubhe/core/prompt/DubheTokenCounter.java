package com.rheinmetal.tianshu.dubhe.core.prompt;

public interface DubheTokenCounter {
    DubheTokenCounter UNAVAILABLE = (requestId, role, content) -> java.util.OptionalInt.empty();

    java.util.OptionalInt countMessageTokens(String requestId, String role, String content);

    static DubheTokenCounter unavailable() {
        return UNAVAILABLE;
    }
}
