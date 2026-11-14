package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolCall;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXToolTurnTest {

    private static final ToolId ECHO = ToolId.of("test", "echo");
    private static final LLMPromptRequestPayload.SamplingPayload SAMPLING =
            LLMPromptRequestPayload.SamplingPayload.empty();

    private static LLMPromptRequestPayload base() {
        return new LLMPromptRequestPayload("req", List.of(
                new LLMPromptRequestPayload.MessageItemPayload("user", "how many sticks?")), false, false, 0,
                SAMPLING);
    }

    private static ToolSpec enabledEcho() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        return new ToolSpec(ECHO, ToolCategory.LOCAL, "Echoes text.", schema, true);
    }

    @Test
    void theCatalogueBecomesASystemMessageOnceAndOnlyWhenToolsExist() {
        AXToolTurn turn = AXToolTurn.start(List.of(enabledEcho()), base(), call -> new JsonObject());
        LLMPromptRequestPayload payload = turn.payload("req", false, 0, null, SAMPLING);
        assertEquals("system", payload.messages().get(0).role());
        assertTrue(payload.messages().get(0).content().contains("test:echo"));
        assertEquals(2, payload.messages().size());

        AXToolTurn withoutTools = AXToolTurn.start(List.of(), base(), call -> new JsonObject());
        assertFalse(withoutTools.toolsAvailable());
        assertEquals(1, withoutTools.payload("req", false, 0, null, SAMPLING).messages().size());
    }

    @Test
    void readableSkillsAreInjectedIntoTheSystemMessage() {
        CapabilityId id = CapabilityId.of("test", "skill");
        CapabilityCatalog.register(new CapabilityDescriptor(id, CapabilityKind.SKILL, CapabilityBinding.LOCAL,
                        "a test skill", ToolSide.CLIENT, ToolPermission.EVERYONE, "test", new JsonObject()),
                (context, args) -> CompletableFuture.completedFuture(new JsonObject()));
        try {
            AXToolTurn turn = AXToolTurn.start(List.of(enabledEcho()), base(), call -> new JsonObject());
            String system = turn.payload("req", false, 0, null, SAMPLING).messages().get(0).content();
            assertTrue(system.contains("test:skill"));
            assertTrue(system.contains("tianshu:capability.read"));
        } finally {
            CapabilityCatalog.unregister(id);
        }
    }

    @Test
    void aPlainAnswerFinishes() {
        AXToolTurn turn = AXToolTurn.start(List.of(enabledEcho()), base(), call -> new JsonObject());
        AXToolTurn.Decision decision = turn.onReply("You have three sticks.", "req", false, 0, null, SAMPLING);
        assertEquals("You have three sticks.", assertInstanceOf(AXToolTurn.Decision.Finish.class, decision).text());
    }

    @Test
    void aToolCallContinuesWithTheResultAppended() {
        AtomicReference<ToolCall> seen = new AtomicReference<>();
        AXToolTurn turn = AXToolTurn.start(List.of(enabledEcho()), base(), call -> {
            seen.set(call);
            JsonObject result = new JsonObject();
            result.addProperty("reply", "three");
            return result;
        });

        AXToolTurn.Decision decision = turn.onReply(
                "{\"tool\":\"test:echo\",\"args\":{\"text\":\"sticks\"}}", "req", false, 0, null, SAMPLING);
        AXToolTurn.Decision.Continue continuation =
                assertInstanceOf(AXToolTurn.Decision.Continue.class, decision);
        assertEquals(ECHO, seen.get().toolId());
        assertEquals(1, turn.toolRounds());

        List<LLMPromptRequestPayload.MessageItemPayload> messages = continuation.next().messages();
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("user", messages.get(3).role());
        assertTrue(messages.get(3).content().contains("three"));
        assertTrue(messages.get(3).content().contains("test:echo"));
    }

    @Test
    void theToolRoundBudgetEndsTheTurn() {
        AXToolTurn turn = AXToolTurn.start(List.of(enabledEcho()), base(), call -> new JsonObject());
        String call = "{\"tool\":\"test:echo\",\"args\":{}}";
        for (int round = 0; round < AXToolTurn.DEFAULT_MAX_TOOL_ROUNDS; round++) {
            assertInstanceOf(AXToolTurn.Decision.Continue.class,
                    turn.onReply(call, "req", false, 0, null, SAMPLING));
        }
        assertInstanceOf(AXToolTurn.Decision.Finish.class,
                turn.onReply(call, "req", false, 0, null, SAMPLING));
    }

    @Test
    void aMalformedCallIsJustAnAnswer() {
        AXToolTurn turn = AXToolTurn.start(List.of(enabledEcho()), base(), call -> new JsonObject());
        assertInstanceOf(AXToolTurn.Decision.Finish.class,
                turn.onReply("so anyway, about the sticks...", "req", false, 0, null, SAMPLING));
    }
}