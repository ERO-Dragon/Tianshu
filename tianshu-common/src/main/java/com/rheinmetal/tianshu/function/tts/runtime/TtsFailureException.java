package com.rheinmetal.tianshu.function.tts.runtime;

public final class TtsFailureException extends RuntimeException {
    private final TtsFailureCode code;

    public TtsFailureException(TtsFailureCode code, String message) {
        super(message == null ? "" : message);
        this.code = code == null ? TtsFailureCode.UNKNOWN : code;
    }

    public TtsFailureCode code() {
        return code;
    }
}
