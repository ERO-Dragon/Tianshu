package com.rheinmetal.tianshu.dubhe.agent.tool;

import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
import org.junit.jupiter.api.BeforeEach;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.dubhe.DubheModule;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
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

class DubheToolRoundHandlerTest {

    @BeforeEach
    void subscribeToFixtures() {
        CapabilitySubscriptions.declare(DubheModule.MOD_ID, CapabilitySubscription.openedAtomic());
    }

    @AfterEach
    void withdrawSubscriptions() {
        CapabilitySubscriptions.withdraw(DubheModule.MOD_ID);
    }


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
        TianshuTools.registerBuiltin(new TianshuTool<JsonObject, JsonObject>() {
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
                result.addProperty("ownerModule", context.caller().id());
                return CompletableFuture.completedFuture(result);
            }
        });
    }

    @Test
    void withoutEnabledToolsThereIsNoToolRound() {
        assertNull(DubheToolRoundHandler.prepare(base(), ignored -> { }, (payload, handler) -> { }, () -> { }));
    }

    @Test
    void theFirstPayloadCarriesTheCatalogueAndDoesNotStream() {
        registerEcho();
        DubheToolRoundHandler handler = DubheToolRoundHandler.prepare(base(), ignored -> { }, (payload, h) -> { }, () -> { });
        assertNotNull(handler);
        LLMPromptRequestPayload first = handler.firstPayload(base());
        assertTrue(first.stream());
        assertTrue(first.messages().get(0).content().contains("test:echo"));
    }

    @Test
    void aToolCallSubmitsAgainAndTheFinalAnswerGoesToDelivery() {
        registerEcho();
        AtomicReference<LLMPromptRequestPayload> submitted = new AtomicReference<>();
        AtomicReference<LLMPromptResultPayload> delivered = new AtomicReference<>();

        DubheLlmRequestHandler delivery = new DubheLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                delivered.set(payload);
            }
        };
        DubheToolRoundHandler handler = DubheToolRoundHandler.prepare(base(), delivery,
                (payload, h) -> submitted.set(payload), () -> { });
        assertNotNull(handler);

        handler.onResult(LLMPromptResultPayload.completed("req",
                "{\"tool\":\"test:echo\",\"args\":{\"text\":\"hi\"}}"));
        assertNotNull(submitted.get(), "a tool call must submit the next request");
        assertNull(delivered.get(), "the call itself is not spoken");
        assertTrue(submitted.get().messages().get(3).content().contains("tool result"));
        // The tool echoes the caller's id, so the expectation follows the module's constant rather than a copy.
        assertTrue(submitted.get().messages().get(3).content()
                .contains("\"ownerModule\":\"" + DubheModule.MOD_ID + "\""));

        handler.onResult(LLMPromptResultPayload.completed("req", "You said hi."));
        assertEquals("You said hi.", delivered.get().text());
    }

    @Test
    void aFailedReplyIsForwardedUntouched() {
        registerEcho();
        AtomicReference<LLMPromptResultPayload> delivered = new AtomicReference<>();
        DubheLlmRequestHandler delivery = new DubheLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                delivered.set(payload);
            }
        };
        DubheToolRoundHandler handler = DubheToolRoundHandler.prepare(base(), delivery, (payload, h) -> { }, () -> { });
        assertNotNull(handler);

        LLMPromptResultPayload failure = LLMPromptResultPayload.failed("req", "e", "boom");
        handler.onResult(failure);
        assertEquals("FAILED", delivered.get().status());
    }

    @Test
    void cancellationCallbacksReachTheDeliveryHandler() {
        registerEcho();
        AtomicReference<DubheTurnCancellation> cancelled = new AtomicReference<>();
        DubheLlmRequestHandler delivery = new DubheLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
            }

            @Override
            public void onCancelled(DubheTurnCancellation cancellation) {
                cancelled.set(cancellation);
            }
        };
        DubheToolRoundHandler handler = DubheToolRoundHandler.prepare(base(), delivery, (payload, h) -> { }, () -> { });
        DubheTurnCancellation reason = DubheTurnCancellation.playerInterrupted("stop");
        handler.onCancelled(reason);
        assertEquals(reason, cancelled.get());
    }
}