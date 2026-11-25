package com.rheinmetal.tianshu.dubhe.agent.skill.shared;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSharedSkillReaderTest {

    private static final ToolId READ = ToolId.of("minecraft", "capability.read");
    private static final DubheSkillContext CONTEXT = new DubheSkillContext("task-1", "读共享技能", new DubheScope(
            "player", "world", "World", DubheScopeKind.LOCAL_WORLD, true));

    @AfterEach
    void cleanUp() {
        TianshuTools.unregister(READ);
    }

    @Test
    void readsASharedSkillThroughThePlatformTool() {
        TianshuTools.register(new TianshuTool<JsonObject, JsonObject>() {
            @Override public ToolId id() { return READ; }
            @Override public ToolCategory category() { return ToolCategory.LOCAL; }
            @Override public String description() { return "test capability reader"; }
            @Override public Type argsType() { return JsonObject.class; }
            @Override public Type resultType() { return JsonObject.class; }
            @Override public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
                JsonObject reply = new JsonObject();
                reply.addProperty("text", "共享技能：" + args.get("id").getAsString());
                return CompletableFuture.completedFuture(reply);
            }
        });

        JsonObject args = new JsonObject();
        args.addProperty("id", "minecraft:player-tools");
        JsonObject result = DubheSharedSkillReader.skill().executor().execute(CONTEXT, args).join();

        assertEquals("OK", result.get("outcome").getAsString());
        assertEquals("共享技能：minecraft:player-tools", result.get("text").getAsString());
    }

    @Test
    void aMissingPlatformToolIsReportedAsFailure() {
        JsonObject args = new JsonObject();
        args.addProperty("id", "minecraft:player-tools");
        JsonObject result = DubheSharedSkillReader.skill().executor().execute(CONTEXT, args).join();

        assertEquals("FAILED", result.get("outcome").getAsString());
        assertTrue(result.has("reason"));
    }
}