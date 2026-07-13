package com.rheinmetal.tianshu.function.tts.runtime;

public record TtsFailure(TtsFailureCode code, String message, Throwable cause) {
    public TtsFailure(TtsFailureCode code, String message) {
        this(code, message, null);
    }

    public TtsFailure {
        code = code == null ? TtsFailureCode.UNKNOWN : code;
        message = message == null ? "" : message.trim();
    }

    public static TtsFailure of(TtsFailureCode code, String message) {
        return new TtsFailure(code, message, null);
    }

    public static TtsFailure fromThrowable(TtsFailureCode code, Throwable throwable) {
        if (throwable == null) {
            return new TtsFailure(code, "", null);
        }
        String message = throwable.getMessage();
        return new TtsFailure(
                code,
                message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message,
                throwable
        );
    }
}
