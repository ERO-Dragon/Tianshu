package com.rheinmetal.tianshu.function.asr.engine;

public record AsrEngineBootstrapStatus(
        Kind kind,
        String messageKey,
        String message
) {
    public AsrEngineBootstrapStatus {
        kind = kind == null ? Kind.WAITING : kind;
        messageKey = messageKey == null ? "" : messageKey.trim();
        message = message == null ? "" : message.trim();
    }

    public static AsrEngineBootstrapStatus ready(String message) {
        return ready("", message);
    }

    public static AsrEngineBootstrapStatus ready(String messageKey, String message) {
        return new AsrEngineBootstrapStatus(Kind.READY, messageKey, message);
    }

    public static AsrEngineBootstrapStatus waiting(String message) {
        return waiting("", message);
    }

    public static AsrEngineBootstrapStatus waiting(String messageKey, String message) {
        return new AsrEngineBootstrapStatus(Kind.WAITING, messageKey, message);
    }

    public static AsrEngineBootstrapStatus failed(String message) {
        return failed("", message);
    }

    public static AsrEngineBootstrapStatus failed(String messageKey, String message) {
        return new AsrEngineBootstrapStatus(Kind.FAILED, messageKey, message);
    }

    public enum Kind {
        READY,
        WAITING,
        FAILED
    }
}
