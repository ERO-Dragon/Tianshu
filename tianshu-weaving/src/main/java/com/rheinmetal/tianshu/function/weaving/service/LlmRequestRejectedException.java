package com.rheinmetal.tianshu.function.weaving.service;

/**
 * A request the lane refuses before the provider is called, carrying the protocol error code.
 *
 * <p>Refusals are the caller's fault and each one has its own code: an empty message list is not the
 * same failure as an unusable payload, and a caller that branches on the code should not have to
 * read the message. Letting these travel as a plain {@link IllegalArgumentException} collapsed every
 * one of them into {@code LLM_REQUEST_FAILED}, which hid the actionable reason.</p>
 */
public final class LlmRequestRejectedException extends IllegalArgumentException {
    /** Used when a rejection names no code of its own. */
    public static final String DEFAULT_CODE = "LLM_REQUEST_FAILED";

    private final String code;

    public LlmRequestRejectedException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? DEFAULT_CODE : code.trim();
    }

    /** The protocol error code this refusal must be reported under. */
    public String code() {
        return code;
    }
}