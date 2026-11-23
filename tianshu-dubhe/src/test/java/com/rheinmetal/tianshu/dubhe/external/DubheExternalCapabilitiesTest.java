package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheExternalCapabilitiesTest {

    private static final ToolCaller CALLER = ToolCaller.mod("test.caller");

    @AfterEach
    void cleanUp() {
        DubheExternalCapabilities.unregister();
    }

    @Test
    void thereIsNoGenericAgentTaskEntry() {
        assertTrue(CapabilityCatalog.find(CapabilityId.of("dubhe", "agent_task")).isEmpty());
    }

    @Test
    void searchIsASkillAndReadsAsText() {
        AtomicReference<LLMPromptRequestPayload> seen = new AtomicReference<>();
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            seen.set(payload);
            handler.onResult(LLMPromptResultPayload.completed(payload.requestId(), "暮色森林需要先做传送门。"));
        }, prompts());

        JsonObject args = new JsonObject();
        args.addProperty("intent", "怎么进入暮色森林");
        JsonObject result = CapabilityCatalog.invoke(DubheExternalCapabilities.SEARCH, args, CALLER, ToolSide.CLIENT).join();

        assertEquals("暮色森林需要先做传送门。", result.get("text").getAsString(), "a SKILL read returns text");
        assertTrue(result.getAsJsonArray("sources").isEmpty(), "no retrieval module means no invented sources");
        assertEquals(512, seen.get().maxTokens().intValue(), "the search skill has a fixed small budget");
        assertEquals(2, seen.get().messages().size(), "system + one user intent");
        assertTrue(seen.get().messages().get(1).content().contains("怎么进入暮色森林"));
    }

    @Test
    void searchIsRegisteredAsSkillAndRelayDoesNotExist() {
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("descriptor test never calls the model");
        }, prompts());

        var descriptor = CapabilityCatalog.find(DubheExternalCapabilities.SEARCH).orElseThrow();
        assertEquals("skill", descriptor.kind().value(), "the whole retrieval is one shareable skill");
        assertTrue(CapabilityCatalog.find(CapabilityId.of("dubhe", "relay")).isEmpty(),
                "relay is a reference inside the diplomacy skill, not an external capability");
    }

    @Test
    void malformedRequestsBecomeAnErrorValue() {
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("must not reach the model");
        }, prompts());

        JsonObject noIntent = new JsonObject();
        JsonObject error = CapabilityCatalog
                .invoke(DubheExternalCapabilities.SEARCH, noIntent, CALLER, ToolSide.CLIENT).join();

        assertFalse(error.get("ok").getAsBoolean());
        assertEquals("FAILED", error.get("code").getAsString());
    }

    private static DubheDelegatePromptRepository prompts() {
        return new DubheDelegatePromptRepository(
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));
    }
}