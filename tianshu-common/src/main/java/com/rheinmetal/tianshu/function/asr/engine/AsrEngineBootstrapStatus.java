package com.rheinmetal.tianshu.function.asr.engine;

public record AsrEngineBootstrapStatus(
        Kind kind,
        String messageKey
) {
    public AsrEngineBootstrapStatus {
        kind = kind == null ? Kind.WAITING : kind;
        messageKey = messageKey == null ? "" : messageKey.trim();
    }

    public static AsrEngineBootstrapStatus ready(String messageKey) {
        return new AsrEngineBootstrapStatus(Kind.READY, messageKey);
    }

    public static AsrEngineBootstrapStatus waiting(String messageKey) {
        return new AsrEngineBootstrapStatus(Kind.WAITING, messageKey);
    }

    public static AsrEngineBootstrapStatus failed(String messageKey) {
        return new AsrEngineBootstrapStatus(Kind.FAILED, messageKey);
    }

    public enum Kind {
        READY,
        WAITING,
        FAILED
    }
}
