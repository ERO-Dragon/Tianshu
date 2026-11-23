package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheExternalCapabilitiesTest {

    private static final ToolCaller CALLER = ToolCaller.mod("test.caller");
    private static final CapabilityId TARGET = CapabilityId.of("mymod", "task");

    @AfterEach
    void cleanUp() {
        CapabilityCatalog.unregister(DubheExternalCapabilities.RESEARCH);
        CapabilityCatalog.unregister(DubheExternalCapabilities.RELAY);
        CapabilityCatalog.unregister(TARGET);
    }

    @Test
    void thereIsNoGenericAgentTaskEntry() {
        assertTrue(CapabilityCatalog.find(CapabilityId.of("dubhe", "agent_task")).isEmpty());
        assertTrue(CapabilityCatalog.list().stream()
                .noneMatch(descriptor -> descriptor.id().toString().toLowerCase(java.util.Locale.ROOT)
                        .contains("agent_task")), "execution must not be exposed as a generic entry");
    }

    @Test
    void researchReturnsAConclusionAndNoSourcesWithoutRetrieval() {
        AtomicReference<LLMPromptRequestPayload> seen = new AtomicReference<>();
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            seen.set(payload);
            handler.onResult(LLMPromptResultPayload.completed(payload.requestId(), "暮色森林需要先做传送门。"));
        }, prompts());

        JsonObject args = new JsonObject();
        args.addProperty("intent", "怎么进入暮色森林");
        JsonObject result = CapabilityCatalog.invoke(DubheExternalCapabilities.RESEARCH, args, CALLER, ToolSide.CLIENT).join();

        assertEquals("暮色森林需要先做传送门。", result.get("conclusion").getAsString());
        assertTrue(result.getAsJsonArray("sources").isEmpty(), "no retrieval module means no invented sources");
        assertEquals(512, seen.get().maxTokens().intValue(), "research has a fixed small budget");
        assertEquals(2, seen.get().messages().size(), "system + one user intent");
        assertTrue(seen.get().messages().get(1).content().contains("怎么进入暮色森林"));
    }

    @Test
    void relayForwardsATaskAndReportsItsState() {
        CapabilityCatalog.register(new CapabilityDescriptor(
                TARGET, CapabilityKind.SKILL, CapabilityBinding.LOCAL, "test task sink",
                ToolSide.CLIENT, ToolPermission.EVERYONE, "test.mod", new JsonObject()), (context, args) -> {
            JsonObject answer = new JsonObject();
            answer.addProperty("taskId", "t-9");
            return CompletableFuture.completedFuture(answer);
        });
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("relay must not call the model");
        }, prompts());

        JsonObject submit = new JsonObject();
        submit.addProperty("target", "mymod:task");
        submit.addProperty("task", "把木头搬进箱子");
        JsonObject forwarded = CapabilityCatalog.invoke(DubheExternalCapabilities.RELAY, submit, CALLER, ToolSide.CLIENT).join();

        assertEquals("t-9", forwarded.get("taskId").getAsString());

        ToolId reader = ToolId.of("mymod", "reader");
        TianshuTools.register(new com.rheinmetal.tianshu.api.tool.TianshuTool<JsonObject, JsonObject>() {
            @Override public ToolId id() { return reader; }
            @Override public ToolCategory category() { return ToolCategory.LOCAL; }
            @Override public String description() { return "state reader"; }
            @Override public Type argsType() { return JsonObject.class; }
            @Override public Type resultType() { return JsonObject.class; }
            @Override public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
                JsonObject answer = new JsonObject();
                answer.addProperty("state", "DONE");
                return CompletableFuture.completedFuture(answer);
            }
        });
        try {
            JsonObject progress = new JsonObject();
            progress.addProperty("target", "mymod:reader");
            progress.addProperty("taskId", "t-9");
            JsonObject state = CapabilityCatalog.invoke(DubheExternalCapabilities.RELAY, progress, CALLER, ToolSide.CLIENT).join();
            assertEquals("DONE", state.get("state").getAsString());
        } finally {
            TianshuTools.unregister(reader);
        }
    }

    @Test
    void malformedRequestsAreRejectedBeforeAnyCall() {
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("must not reach the model");
        }, prompts());

        JsonObject noIntent = new JsonObject();
        JsonObject researchError = CapabilityCatalog
                .invoke(DubheExternalCapabilities.RESEARCH, noIntent, CALLER, ToolSide.CLIENT).join();
        assertFalse(researchError.get("ok").getAsBoolean(), "a malformed research request becomes an error value");
        JsonObject noTarget = new JsonObject();
        JsonObject relayError = CapabilityCatalog
                .invoke(DubheExternalCapabilities.RELAY, noTarget, CALLER, ToolSide.CLIENT).join();
        assertFalse(relayError.get("ok").getAsBoolean(), "a malformed relay request becomes an error value");
    }

    private static void assertThrows(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static DubheDelegatePromptRepository prompts() {
        return new DubheDelegatePromptRepository(
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));
    }
}