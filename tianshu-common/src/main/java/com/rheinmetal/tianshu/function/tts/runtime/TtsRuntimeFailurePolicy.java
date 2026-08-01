package com.rheinmetal.tianshu.function.tts.runtime;

public final class TtsRuntimeFailurePolicy {
    private TtsRuntimeFailurePolicy() {
    }

    public static TtsFailure classify(TtsFailureCode fallbackCode, Throwable throwable) {
        rethrowFatal(throwable);
        TtsFailureException structured = findStructuredFailure(throwable);
        if (structured != null) {
            return TtsFailure.fromThrowable(structured.code(), structured);
        }
        return TtsFailure.fromThrowable(fallbackCode, throwable);
    }

    private static TtsFailureException findStructuredFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TtsFailureException structured) {
                return structured;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
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
