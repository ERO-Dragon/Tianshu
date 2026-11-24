package com.rheinmetal.tianshu.dubhe.agent.skill.relay;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheRelaySkillTest {

    private static final DubheScope SCOPE =
            new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);
    private static final DubheSkillContext CONTEXT = new DubheSkillContext("task-1", "问问女仆", SCOPE);

    @Test
    void theSpecIsWellFormedForTheAgentDirectory() {
        DubheSkillSpec spec = DubheRelaySkill.spec();

        assertEquals("relay", spec.id());
        assertTrue(spec.reportSchema().contains("outcome"));
        assertTrue(spec.reportSchema().contains("progress"));
        assertTrue(spec.inputSchema().has("properties"));
    }

    @Test
    void malformedArgumentsFailWithACodeInsteadOfCallingAnything() {
        DubheSkill skill = new DubheRelaySkill(null).skill();

        assertEquals("INVALID_TARGET", outcome(skill, args("submit", "no-namespace", "做点事", "")));
        assertEquals("INVALID_ACTION", outcome(skill, args("explode", "mymod:task", "", "")));
        assertEquals("INVALID_ARGS", outcome(skill, args("submit", "mymod:task", "", "")));
        assertEquals("INVALID_ARGS", outcome(skill, args("progress", "mymod:task", "", "")));
    }

    @Test
    void progressMapsKnownStatesAndNeverTreatsAnUnknownStateAsDone() {
        ToolId done = ToolId.of("mymod", "done_task");
        ToolId odd = ToolId.of("mymod", "odd_task");
        register(done, "DONE");
        register(odd, "ALMOST");
        try {
            DubheSkill skill = new DubheRelaySkill(
                    () -> ToolCaller.agent("dubhe", false, com.rheinmetal.tianshu.api.tool.ToolAutonomy.READ_ONLY))
                    .skill();

            JsonObject doneResult = call(skill, args("progress", "mymod:done_task", "", "t-1"));
            assertEquals("OK", doneResult.get("outcome").getAsString());
            assertEquals("DONE", doneResult.get("progress").getAsString());

            JsonObject oddResult = call(skill, args("progress", "mymod:odd_task", "", "t-2"));
            assertEquals("UNKNOWN", oddResult.get("progress").getAsString(),
                    "an unrecognised state must not be reported as completion");
        } finally {
            TianshuTools.unregister(done);
            TianshuTools.unregister(odd);
        }
    }

    @Test
    void anUnconfirmedTierAsksFirstAndCallsNothing() {
        ToolId id = ToolId.of("mymod", "consent_task");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        TianshuTools.register(new TianshuTool<JsonObject, JsonObject>() {
            @Override public ToolId id() { return id; }
            @Override public ToolCategory category() { return ToolCategory.LOCAL; }
            @Override public String description() { return "consent test reader"; }
            @Override public Type argsType() { return JsonObject.class; }
            @Override public Type resultType() { return JsonObject.class; }
            @Override public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
                calls.incrementAndGet();
                JsonObject result = new JsonObject();
                result.addProperty("state", "DONE");
                return CompletableFuture.completedFuture(result);
            }
        });
        try {
            com.rheinmetal.tianshu.dubhe.settings.DubheAutonomyConsent consent =
                    new com.rheinmetal.tianshu.dubhe.settings.DubheAutonomyConsent(
                            new com.rheinmetal.tianshu.dubhe.settings.DubheAutonomyConsent.Storage() {
                                private final java.util.Map<String, String> values = new java.util.HashMap<>();
                                @Override public String get(String key, String fallback) {
                                    return values.getOrDefault(key, fallback);
                                }
                                @Override public void set(String key, String value) { values.put(key, value); }
                            });
            DubheSkill skill = new DubheRelaySkill(
                    () -> ToolCaller.agent("dubhe", false, com.rheinmetal.tianshu.api.tool.ToolAutonomy.READ_ONLY),
                    consent).skill();

            JsonObject pending = call(skill, args("progress", "mymod:consent_task", "", "t-1"));
            assertEquals("NEEDS_CONSENT", pending.get("outcome").getAsString());
            assertEquals("READ_ONLY", pending.get("tier").getAsString());
            assertEquals(0, calls.get(), "nothing may leave the module before the player agrees");

            consent.confirm(com.rheinmetal.tianshu.api.tool.ToolAutonomy.READ_ONLY);
            JsonObject allowed = call(skill, args("progress", "mymod:consent_task", "", "t-1"));
            assertEquals("OK", allowed.get("outcome").getAsString());
            assertEquals(1, calls.get(), "after confirmation the call goes through");
        } finally {
            TianshuTools.unregister(id);
        }
    }

    @Test
    void aRefusedCallCarriesThePlayerFacingReason() {
        com.rheinmetal.tianshu.dubhe.dialogue.output.DubheReplyTextRepository texts =
                new com.rheinmetal.tianshu.dubhe.dialogue.output.DubheReplyTextRepository(
                        com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider.fixed(
                                com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage.ZH_CN));
        DubheSkill skill = new DubheRelaySkill(
                () -> ToolCaller.agent("dubhe", false, com.rheinmetal.tianshu.api.tool.ToolAutonomy.READ_ONLY),
                null, texts).skill();

        JsonObject result = call(skill, args("progress", "mymod:ghost", "", "t-1"));

        assertEquals("CALL_REFUSED", result.get("reason").getAsString());
        assertEquals("没找到能办这事的对象。", result.get("block").getAsString(),
                "the speech layer gets a game-facing reason, never the tool error code");
    }

    @Test
    void listDiscoversOtherModulesFunctionsAndHidesOurOwn() {
        com.rheinmetal.tianshu.api.capability.CapabilityId miner =
                com.rheinmetal.tianshu.api.capability.CapabilityId.of("othermod", "miner");
        com.rheinmetal.tianshu.api.capability.CapabilityCatalog.register(
                new com.rheinmetal.tianshu.api.capability.CapabilityDescriptor(
                        miner,
                        com.rheinmetal.tianshu.api.capability.CapabilityKind.SKILL,
                        com.rheinmetal.tianshu.api.capability.CapabilityBinding.LOCAL,
                        "mines a block for the caller",
                        com.rheinmetal.tianshu.api.tool.ToolSide.CLIENT,
                        com.rheinmetal.tianshu.api.tool.ToolPermission.EVERYONE,
                        "other.mod",
                        new JsonObject()),
                (context, args) -> CompletableFuture.completedFuture(new JsonObject()));
        com.rheinmetal.tianshu.dubhe.external.DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("list must not call the model");
        }, new com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository(
                com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider.fixed(
                        com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage.ZH_CN)));
        try {
            DubheSkill skill = new DubheRelaySkill(
                    () -> ToolCaller.agent("dubhe", false, com.rheinmetal.tianshu.api.tool.ToolAutonomy.READ_ONLY))
                    .skill();

            JsonObject listed = call(skill, args("list", "", "", ""));

            assertEquals("OK", listed.get("outcome").getAsString());
            boolean sawMiner = false;
            for (com.google.gson.JsonElement element : listed.getAsJsonArray("targets")) {
                JsonObject row = element.getAsJsonObject();
                String id = row.get("id").getAsString();
                assertTrue(!id.startsWith("dubhe:"), "our own functions are not delegation targets: " + id);
                if ("othermod:miner".equals(id)) {
                    sawMiner = true;
                    assertEquals("mines a block for the caller", row.get("description").getAsString());
                }
            }
            assertTrue(sawMiner, "another module function must be discoverable");
        } finally {
            com.rheinmetal.tianshu.api.capability.CapabilityCatalog.unregister(miner);
            com.rheinmetal.tianshu.dubhe.external.DubheExternalCapabilities.unregister();
        }
    }

    @Test
    void describeFetchesTheReferenceOnlyForAChosenTarget() {
        com.rheinmetal.tianshu.api.capability.CapabilityId miner =
                com.rheinmetal.tianshu.api.capability.CapabilityId.of("othermod", "miner");
        JsonObject attributes = new JsonObject();
        attributes.addProperty("schemaVersion", 1);
        attributes.addProperty("inputs", "block position + face");
        com.rheinmetal.tianshu.api.capability.CapabilityCatalog.register(
                new com.rheinmetal.tianshu.api.capability.CapabilityDescriptor(
                        miner,
                        com.rheinmetal.tianshu.api.capability.CapabilityKind.SKILL,
                        com.rheinmetal.tianshu.api.capability.CapabilityBinding.LOCAL,
                        "mines a block for the caller",
                        com.rheinmetal.tianshu.api.tool.ToolSide.CLIENT,
                        com.rheinmetal.tianshu.api.tool.ToolPermission.EVERYONE,
                        "other.mod",
                        attributes),
                (context, args) -> CompletableFuture.completedFuture(new JsonObject()));
        try {
            DubheSkill skill = new DubheRelaySkill(
                    () -> ToolCaller.agent("dubhe", false, com.rheinmetal.tianshu.api.tool.ToolAutonomy.READ_ONLY))
                    .skill();

            JsonObject described = call(skill, args("describe", "othermod:miner", "", ""));
            assertEquals("OK", described.get("outcome").getAsString());
            assertEquals("block position + face",
                    described.getAsJsonObject("attributes").get("inputs").getAsString());

            JsonObject unknown = call(skill, args("describe", "othermod:ghost", "", ""));
            assertEquals("TARGET_UNKNOWN", unknown.get("reason").getAsString());
        } finally {
            com.rheinmetal.tianshu.api.capability.CapabilityCatalog.unregister(miner);
        }
    }

    private static void register(ToolId id, String state) {
        TianshuTools.register(new TianshuTool<JsonObject, JsonObject>() {
            @Override
            public ToolId id() {
                return id;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.LOCAL;
            }

            @Override
            public String description() {
                return "test task reader";
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
                result.addProperty("state", state);
                return CompletableFuture.completedFuture(result);
            }
        });
    }

    private static String outcome(DubheSkill skill, JsonObject args) {
        JsonObject result = call(skill, args);
        return result.has("reason") ? result.get("reason").getAsString() : result.get("outcome").getAsString();
    }

    private static JsonObject call(DubheSkill skill, JsonObject args) {
        return skill.executor().execute(CONTEXT, args).join();
    }

    private static JsonObject args(String action, String target, String task, String taskId) {
        JsonObject args = new JsonObject();
        args.addProperty("action", action);
        args.addProperty("target", target);
        args.addProperty("task", task);
        args.addProperty("taskId", taskId);
        return args;
    }
}
