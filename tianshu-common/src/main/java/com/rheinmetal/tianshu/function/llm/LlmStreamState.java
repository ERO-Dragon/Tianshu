package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;

import java.util.List;
import java.util.function.Consumer;

final class LlmStreamState {
    private final String requestId;
    private final List<LLMPromptResultPayload.RagHitPayload> ragHits;
    private final Consumer<LLMPromptStreamChunkPayload> publisher;
    private final StringBuilder visible = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private int index;
    private boolean ragHitsPublished;
    private boolean terminal;

    LlmStreamState(
            String requestId,
            List<LLMPromptResultPayload.RagHitPayload> ragHits,
            Consumer<LLMPromptStreamChunkPayload> publisher
    ) {
        this.requestId = requestId == null ? "" : requestId;
        this.ragHits = ragHits == null ? List.of() : ragHits;
        this.publisher = publisher == null ? ignored -> { } : publisher;
    }

    synchronized void onVisible(String token) {
        if (terminal || token == null || token.isEmpty()) {
            return;
        }
        publishRagHitsIfNeeded();
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

    synchronized void publishRagHitsIfNeeded() {
        if (terminal || ragHitsPublished || ragHits.isEmpty()) {
            return;
        }
        ragHitsPublished = true;
        publisher.accept(LLMPromptStreamChunkPayload.chunk(requestId, "", index++, List.copyOf(ragHits)));
    }

    synchronized TerminalSnapshot terminal(String visibleFallback, String... thinkingFallbacks) {
        publishRagHitsIfNeeded();
        terminal = true;
        return new TerminalSnapshot(
                visible.length() > 0 ? visible.toString() : clean(visibleFallback),
                resolvedThinking(thinkingFallbacks),
                List.copyOf(ragHits),
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
            List<LLMPromptResultPayload.RagHitPayload> ragHits,
            int nextIndex
    ) {
        TerminalSnapshot {
            visibleText = clean(visibleText);
            thinkingContent = clean(thinkingContent);
            ragHits = ragHits == null ? List.of() : List.copyOf(ragHits);
        }
    }
}
