package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRequestHandler;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXToolRoundHandlerTest {

    private static final ToolId ECHO = ToolId.of("test", "echo");

    @AfterEach
    void clean() {
        TianshuTools.unregister(ECHO);
    }

    private static LLMPromptRequestPayload base() {
        return new LLMPromptRequestPayload("req", List.of(
                new LLMPromptRequestPayload.MessageItemPayload("user", "hi")), true, false, 0,
                LLMPromptRequestPayload.SamplingPayload.empty());
    }

    private static void registerEcho() {
        TianshuTools.register(new TianshuTool<JsonObject, JsonObject>() {
            @Override
            public ToolId id() {
                return ECHO;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.LOCAL;
            }

            @Override
            public String description() {
                return "Echo; a test double.";
            }

            @Override
            public Type argsType() {
                return JsonObject.class;
            }

            @Override
            public Type resultType() {
                return JsonObject.class;
            }

            @Override
            public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
                JsonObject result = new JsonObject();
                result.add("args", args);
                return CompletableFuture.completedFuture(result);
            }
        });
    }

    @Test
    void withoutEnabledToolsThereIsNoToolRound() {
        assertNull(AXToolRoundHandler.prepare(base(), ignored -> { }, (payload, handler) -> { }));
    }

    @Test
    void theFirstPayloadCarriesTheCatalogueAndDoesNotStream() {
        registerEcho();
        AXToolRoundHandler handler = AXToolRoundHandler.prepare(base(), ignored -> { }, (payload, h) -> { });
        assertNotNull(handler);
        LLMPromptRequestPayload first = handler.firstPayload(base());
        assertEquals(false, first.stream());
        assertTrue(first.messages().get(0).content().contains("test:echo"));
    }

    @Test
    void aToolCallSubmitsAgainAndTheFinalAnswerGoesToDelivery() {
        registerEcho();
        AtomicReference<LLMPromptRequestPayload> submitted = new AtomicReference<>();
        AtomicReference<LLMPromptResultPayload> delivered = new AtomicReference<>();

        AXLlmRequestHandler delivery = new AXLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                delivered.set(payload);
            }
        };
        AXToolRoundHandler handler = AXToolRoundHandler.prepare(base(), delivery,
                (payload, h) -> submitted.set(payload));
        assertNotNull(handler);

        handler.onResult(LLMPromptResultPayload.completed("req",
                "{\"tool\":\"test:echo\",\"args\":{\"text\":\"hi\"}}"));
        assertNotNull(submitted.get(), "a tool call must submit the next request");
        assertNull(delivered.get(), "the call itself is not spoken");
        assertTrue(submitted.get().messages().get(3).content().contains("tool result"));

        handler.onResult(LLMPromptResultPayload.completed("req", "You said hi."));
        assertEquals("You said hi.", delivered.get().text());
    }

    @Test
    void aFailedReplyIsForwardedUntouched() {
        registerEcho();
        AtomicReference<LLMPromptResultPayload> delivered = new AtomicReference<>();
        AXLlmRequestHandler delivery = new AXLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                delivered.set(payload);
            }
        };
        AXToolRoundHandler handler = AXToolRoundHandler.prepare(base(), delivery, (payload, h) -> { });
        assertNotNull(handler);

        LLMPromptResultPayload failure = new LLMPromptResultPayload("req", "FAILED", "", "", "e", "boom", null);
        handler.onResult(failure);
        assertEquals("FAILED", delivered.get().status());
    }

    @Test
    void cancellationCallbacksReachTheDeliveryHandler() {
        registerEcho();
        AtomicReference<AXTurnCancellation> cancelled = new AtomicReference<>();
        AXLlmRequestHandler delivery = new AXLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
            }

            @Override
            public void onCancelled(AXTurnCancellation cancellation) {
                cancelled.set(cancellation);
            }
        };
        AXToolRoundHandler handler = AXToolRoundHandler.prepare(base(), delivery, (payload, h) -> { });
        AXTurnCancellation reason = AXTurnCancellation.playerInterrupted("stop");
        handler.onCancelled(reason);
        assertEquals(reason, cancelled.get());
    }
}