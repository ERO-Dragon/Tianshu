package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmStreamStateTest {
    @Test
    void serializesVisibleThinkingAndTerminalStateAcrossCallbacks() {
        List<LLMPromptStreamChunkPayload> published = new ArrayList<>();
        LlmStreamState state = new LlmStreamState("request", published::add);

        CompletableFuture<Void> visible = CompletableFuture.runAsync(() -> state.onVisible("answer"));
        CompletableFuture<Void> thinking = CompletableFuture.runAsync(() -> state.onThinking("reasoning"));
        CompletableFuture.allOf(visible, thinking).join();
        LlmStreamState.TerminalSnapshot terminal = state.terminal("", "");

        assertEquals("answer", terminal.visibleText());
        assertEquals("reasoning", terminal.thinkingContent());
        assertEquals(2, terminal.nextIndex());
        assertEquals(2, published.size());
    }

    @Test
    void terminalFallsBackToReportedTextAndIgnoresLaterTokens() {
        List<LLMPromptStreamChunkPayload> published = new ArrayList<>();
        LlmStreamState state = new LlmStreamState("request", published::add);

        LlmStreamState.TerminalSnapshot terminal = state.terminal("whole answer", "late reasoning");
        state.onVisible("after-terminal");

        assertEquals("whole answer", terminal.visibleText());
        assertEquals("late reasoning", terminal.thinkingContent());
        assertEquals(0, published.size(), "the terminal frame belongs to the handler, not to the token state");
    }
}
