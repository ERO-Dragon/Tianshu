package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmThinkingContentFilterTest {
    @Test
    void stripsCompleteThinkingBlock() {
        assertEquals("answer", LlmThinkingContentFilter.strip("<think>reasoning</think>answer"));
    }

    @Test
    void keepsTextWhenNoThinkingBlockExists() {
        assertEquals("plain answer", LlmThinkingContentFilter.strip("plain answer"));
    }

    @Test
    void stripsThinkingBlockAcrossStreamChunks() {
        LlmThinkingContentFilter filter = new LlmThinkingContentFilter();

        String visible = filter.append("<thi")
                + filter.append("nk>reason")
                + filter.append("ing</thi")
                + filter.append("nk>answer")
                + filter.flush();

        assertEquals("answer", visible);
    }

    @Test
    void preservesPotentialOpenTagUntilItIsResolved() {
        LlmThinkingContentFilter filter = new LlmThinkingContentFilter();

        String visible = filter.append("abc<thi")
                + filter.append("s is visible")
                + filter.flush();

        assertEquals("abc<this is visible", visible);
    }
}
