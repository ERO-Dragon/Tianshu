package com.rheinmetal.tianshu.function.tts.runtime;

public final class TtsRuntimeFailurePolicy {
    private TtsRuntimeFailurePolicy() {
    }

    public static TtsFailure classify(TtsFailureCode fallbackCode, Throwable throwable) {
        rethrowFatal(throwable);
        return TtsFailure.fromThrowable(fallbackCode, throwable);
    }

    public static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (throwable instanceof Error error && !(error instanceof LinkageError)) {
            throw error;
        }
    }
}
