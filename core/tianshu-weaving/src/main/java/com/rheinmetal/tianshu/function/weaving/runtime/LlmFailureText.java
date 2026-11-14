package com.rheinmetal.tianshu.function.weaving.runtime;

/**
 * The one line a failure is reported with.
 *
 * <p>A throwable may carry no message at all — a bare {@link IllegalStateException} from a library
 * frequently does — and every place that reports a failure (a protocol error, a status line, a
 * diagnostic) has to say <em>something</em>. The type is that something; an empty string never is.</p>
 */
public final class LlmFailureText {
    /** Fallback for a null failure, which means the caller lost the cause rather than that nothing went wrong. */
    private static final String UNKNOWN = "unknown failure";

    private LlmFailureText() {
    }

    public static String of(Throwable failure) {
        if (failure == null) {
            return UNKNOWN;
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}