package com.rheinmetal.tianshu.function.weaving.service;

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

    @Test
    void readsTheOtherReasoningSpellingsSomeRelaysUse() {
        assertEquals("think", OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{\"reasoning\":\"think\"}}]}").thinking());
        assertEquals("think", OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_text\":\"think\"}}]}").thinking());
    }

    @Test
    void prefersTheFirstReasoningFieldWhenAGatewaySendsTwo() {
        // chutes.ai returns both fields with the same content; taking the first avoids duplication.
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"one\",\"reasoning\":\"two\"}}]}"
        );

        assertEquals("one", event.thinking());
    }

    @Test
    void readsTheNestedOpenAiCacheReadAndDerivesTheMiss() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":6}}}"
        );

        assertEquals(6, event.cacheHitTokens());
        assertEquals(4, event.cacheMissTokens(), "the rest of the prompt is the miss");
    }

    @Test
    void readsTheDeepSeekCacheFields() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,"
                        + "\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":3}}"
        );

        assertEquals(7, event.cacheHitTokens());
        assertEquals(3, event.cacheMissTokens());
    }

    @Test
    void readsTheFlatCachedTokensSomeProvidersUse() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":2,"
                        + "\"cached_tokens\":8}}"
        );

        assertEquals(8, event.cacheHitTokens());
        assertEquals(12, event.cacheMissTokens());
    }

    @Test
    void acceptsUsageInsideTheLegacyChoiceObject() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}]}"
        );

        assertEquals(5, event.promptTokens());
        assertEquals(1, event.completionTokens());
    }

    @Test
    void readsTheFinishReason() {
        OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}"
        );

        assertEquals("content_filter", event.finishReason());
        assertEquals("", OpenAiChatStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}").finishReason());
    }
}
