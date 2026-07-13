package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmStreamStateTest {
    @Test
    void serializesVisibleThinkingAndTerminalStateAcrossCallbacks() {
        List<LLMPromptStreamChunkPayload> published = new ArrayList<>();
        List<LLMPromptResultPayload.RagHitPayload> ragHits = List.of(
                LLMPromptResultPayload.RagHitPayload.of("memory", List.of())
        );
        LlmStreamState state = new LlmStreamState("request", ragHits, published::add);

        CompletableFuture<Void> visible = CompletableFuture.runAsync(() -> state.onVisible("answer"));
        CompletableFuture<Void> thinking = CompletableFuture.runAsync(() -> state.onThinking("reasoning"));
        CompletableFuture.allOf(visible, thinking).join();
        LlmStreamState.TerminalSnapshot terminal = state.terminal("", "");

        assertEquals("answer", terminal.visibleText());
        assertEquals("reasoning", terminal.thinkingContent());
        assertEquals(ragHits, terminal.ragHits());
        assertEquals(3, terminal.nextIndex());
        assertEquals(3, published.size());
    }
}
