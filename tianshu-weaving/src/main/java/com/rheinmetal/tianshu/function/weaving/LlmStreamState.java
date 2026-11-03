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
 */
final class LlmStreamState {
    private final String requestId;
    private final Consumer<LLMPromptStreamChunkPayload> publisher;
    private final StringBuilder visible = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private int index;
    private boolean terminal;

    LlmStreamState(String requestId, Consumer<LLMPromptStreamChunkPayload> publisher) {
        this.requestId = requestId == null ? "" : requestId;
        this.publisher = publisher == null ? ignored -> { } : publisher;
    }

    synchronized void onVisible(String token) {
        if (terminal || token == null || token.isEmpty()) {
            return;
        }
        visible.append(token);
        publisher.accept(LLMPromptStreamChunkPayload.chunk(requestId, token, index++));
    }

    synchronized void onThinking(String token) {
        if (terminal || token == null || token.isEmpty()) {
            return;
        }
        thinking.append(token);
        publisher.accept(LLMPromptStreamChunkPayload.thinking(requestId, token, index++));
    }

    /**
     * Closes the stream and returns the accumulated answer once.
     *
     * @param visibleFallback text to fall back on when no token was published (a non-streaming
     *                        provider or a cancelled exchange)
     * @param thinkingFallbacks in priority order: what the transport or the provider reported at the end
     */
    synchronized TerminalSnapshot terminal(String visibleFallback, String... thinkingFallbacks) {
        terminal = true;
        return new TerminalSnapshot(
                visible.length() > 0 ? visible.toString() : clean(visibleFallback),
                resolvedThinking(thinkingFallbacks),
                index
        );
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
