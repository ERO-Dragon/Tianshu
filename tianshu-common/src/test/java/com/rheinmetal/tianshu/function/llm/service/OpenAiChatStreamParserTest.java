package com.rheinmetal.tianshu.function.llm.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatStreamParserTest {

    @Test
    void parsesIncrementalContentFrame() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"
        );

        assertEquals("Hel", event.text());
        assertEquals("", event.thinking());
        assertFalse(event.done());
        assertNull(event.promptTokens());
        assertNull(event.completionTokens());
    }

    @Test
    void recognizesDoneMarker() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse("data: [DONE]");

        assertTrue(event.done());
        assertEquals("", event.text());
    }

    @Test
    void ignoresBlankCommentsAndNonDataFields() {
        assertNull(OpenAiChatStreamParser.parse(""));
        assertNull(OpenAiChatStreamParser.parse("   "));
        assertNull(OpenAiChatStreamParser.parse(": keep-alive"));
        assertNull(OpenAiChatStreamParser.parse("event: message"));
        assertNull(OpenAiChatStreamParser.parse("data:"));
    }

    @Test
    void ignoresMalformedPayloadInsteadOfFailingTheStream() {
        assertNull(OpenAiChatStreamParser.parse("data: {not json"));
        assertNull(OpenAiChatStreamParser.parse("data: not-json"));
        assertNull(OpenAiChatStreamParser.parse("data: [DONE"));
    }

    @Test
    void reportsUsageFrameWithoutContent() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}"
        );

        assertEquals("", event.text());
        assertEquals(7, event.promptTokens());
        assertEquals(3, event.completionTokens());
    }

    @Test
    void readsReasoningContentFrame() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}"
        );

        assertEquals("think", event.thinking());
        assertEquals("", event.text());
    }
}
