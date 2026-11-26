package com.rheinmetal.tianshu.dubhe.agent.skill.host;

import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
import org.junit.jupiter.api.BeforeEach;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheHostToolSkillsTest {

    @BeforeEach
    void subscribeToFixtures() {
        CapabilitySubscriptions.declare("dubhe", CapabilitySubscription.openedAtomic());
    }

    @AfterEach
    void withdrawSubscriptions() {
        CapabilitySubscriptions.withdraw("dubhe");
    }


    private static final ToolId ECHO = ToolId.of("tianshu", "test.echo");
    private static final ToolId DISABLED = ToolId.of("tianshu", "test.disabled");
    private static final ToolId FULL = ToolId.of("tianshu", "test.full");
    private static final ToolCaller READ_ONLY = ToolCaller.agent("dubhe", false, ToolAutonomy.READ_ONLY);
    private static final DubheSkillContext CONTEXT = new DubheSkillContext("task-1", "用工具", new DubheScope(
            "player", "world", "World", DubheScopeKind.LOCAL_WORLD, true));

    @AfterEach
    void cleanUp() {
        TianshuTools.unregister(ECHO);
        TianshuTools.unregister(DISABLED);
        TianshuTools.unregister(FULL);
    }

    @Test
    void hostToolsBecomeAgentToolsWithTheirOwnSchemaAndCallPath() {
        register(ECHO, ToolAutonomy.READ_ONLY);
        List<DubheSkill> skills = DubheHostToolSkills.fromCatalog(READ_ONLY);

        DubheSkill echo = skills.stream().filter(skill -> skill.id().equals("tianshu_test_echo"))
                .findFirst().orElseThrow();
        JsonObject args = new JsonObject();
        args.addProperty("text", "hi");
        JsonObject result = echo.executor().execute(CONTEXT, args).join();

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals("hi", result.getAsJsonObject("result").get("echo").getAsString());
    }

    @Test
    void disabledToolsAndHigherTiersAreNotInTheDirectory() {
        register(ECHO, ToolAutonomy.READ_ONLY);
        register(DISABLED, ToolAutonomy.READ_ONLY);
        TianshuTools.setEnabled(DISABLED, false);
        register(FULL, ToolAutonomy.FULL);

        List<String> ids = DubheHostToolSkills.fromCatalog(READ_ONLY).stream().map(DubheSkill::id).toList();

        assertTrue(ids.contains("tianshu_test_echo"));
        assertFalse(ids.contains("tianshu_test_disabled"), "a switched-off tool cannot be called");
        assertFalse(ids.contains("tianshu_test_full"), "a higher tier tool must not be visible");
    }

    @Test
    void bodyToolsAreNeverOfferedToTianshu() {
        assertFalse(DubheHostToolSkills.fromCatalog(READ_ONLY).stream()
                .anyMatch(skill -> skill.id().startsWith("tianshu.body.")));
    }

    private static void register(ToolId id, ToolAutonomy autonomy) {
        TianshuTools.registerBuiltin(new TianshuTool<JsonObject, JsonObject>() {
            @Override public ToolId id() { return id; }
            @Override public ToolCategory category() { return ToolCategory.LOCAL; }
            @Override public String description() { return "test tool " + id; }
            @Override public ToolAutonomy requiredAutonomy() { return autonomy; }
            @Override public Type argsType() { return JsonObject.class; }
            @Override public Type resultType() { return JsonObject.class; }
            @Override public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
                JsonObject result = new JsonObject();
                result.addProperty("echo", args.has("text") ? args.get("text").getAsString() : "");
                return CompletableFuture.completedFuture(result);
            }
        });
    }
}