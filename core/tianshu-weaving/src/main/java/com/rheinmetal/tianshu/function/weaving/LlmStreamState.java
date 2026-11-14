package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;

import java.util.function.Consumer;

/**
 * The single mutable state behind one streaming answer.
 *
 * <p>Token callbacks can arrive from different threads (the transport reader and the terminal
 * callback), so both the published chunks and the terminal snapshot are derived from this one
 * synchronized object. That is what keeps a terminal frame from dropping text that was already
 * published.</p>
 *
 * <p>Tokens are batched for a short window before publishing. The protocol runtime rate-limits
 * response envelopes per source/target (200/s by default), and a fast provider can emit several
 * hundred tokens per second; publishing one envelope per token would hit that limit and lose text.
 * A 25 ms window keeps the envelope rate around 40/s for text (plus the same for thinking) while
 * leaving the first token immediate — a slow stream still publishes token by token because the
 * window has already elapsed when the next token arrives.</p>
 */
final class LlmStreamState {
    /** Coalescing window; at most one text and one thinking envelope per window. */
    private static final long FLUSH_INTERVAL_NANOS = 25_000_000L;

    private final String requestId;
    private final Consumer<LLMPromptStreamChunkPayload> publisher;
    private final StringBuilder visible = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder pendingVisible = new StringBuilder();
    private final StringBuilder pendingThinking = new StringBuilder();
    private int index;
    private boolean terminal;
    /** Set to "long ago" so the first token is published immediately. */
    private long lastFlushNanos = System.nanoTime() - FLUSH_INTERVAL_NANOS;

    LlmStreamState(String requestId, Consumer<LLMPromptStreamChunkPayload> publisher) {
        this.requestId = requestId == null ? "" : requestId;
        this.publisher = publisher == null ? ignored -> { } : publisher;
    }

    synchronized void onVisible(String token) {
        if (terminal || token == null || token.isEmpty()) {
            return;
        }
        visible.append(token);
        pendingVisible.append(token);
        flushIfDue();
    }

    synchronized void onThinking(String token) {
        if (terminal || token == null || token.isEmpty()) {
            return;
        }
        thinking.append(token);
        pendingThinking.append(token);
        flushIfDue();
    }

    /**
     * Closes the stream and returns the accumulated answer once.
     *
     * <p>Whatever is still buffered is published first, so the terminal frame's index always follows
     * the last published chunk.</p>
     *
     * @param visibleFallback text to fall back on when no token was published (a non-streaming
     *                        provider or a cancelled exchange)
     * @param thinkingFallbacks in priority order: what the transport or the provider reported at the end
     */
    synchronized TerminalSnapshot terminal(String visibleFallback, String... thinkingFallbacks) {
        flushPending(System.nanoTime());
        terminal = true;
        return new TerminalSnapshot(
                visible.length() > 0 ? visible.toString() : clean(visibleFallback),
                resolvedThinking(thinkingFallbacks),
                index
        );
    }

    private void flushIfDue() {
        long now = System.nanoTime();
        if (now - lastFlushNanos < FLUSH_INTERVAL_NANOS) {
            return;
        }
        flushPending(now);
    }

    private void flushPending(long now) {
        if (pendingThinking.length() > 0) {
            publisher.accept(LLMPromptStreamChunkPayload.thinking(
                    requestId, pendingThinking.toString(), index++));
            pendingThinking.setLength(0);
        }
        if (pendingVisible.length() > 0) {
            publisher.accept(LLMPromptStreamChunkPayload.chunk(
                    requestId, pendingVisible.toString(), index++));
            pendingVisible.setLength(0);
        }
        lastFlushNanos = now;
    }

    private String resolvedThinking(String[] fallbacks) {
        if (thinking.length() > 0) {
            return thinking.toString();
        }
        if (fallbacks != null) {
            for (String fallback : fallbacks) {
                if (fallback != null && !fallback.isEmpty()) {
                    return fallback;
                }
            }
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    record TerminalSnapshot(
            String visibleText,
            String thinkingContent,
            int nextIndex
    ) {
        TerminalSnapshot {
            visibleText = clean(visibleText);
            thinkingContent = clean(thinkingContent);
        }
    }
}