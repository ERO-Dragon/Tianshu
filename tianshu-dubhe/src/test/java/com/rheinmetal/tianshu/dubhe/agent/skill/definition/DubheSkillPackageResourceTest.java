package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillRegistry;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSkillPackageResourceTest {

    private static final DubheSkillContext CONTEXT =
            new DubheSkillContext("task-1", "帮我把木头搬进箱子", new DubheScope(
                    "player", "world", "World", DubheScopeKind.LOCAL_WORLD, true));

    @Test
    void theWorkPackageIsIndexedAndLoadsWithItsReferences() {
        List<DubheSkillDefinition> definitions = DubheSkillPackageResourceLoader.loadBuiltinDefinitions();

        assertEquals(1, definitions.size());
        DubheSkillDefinition work = definitions.get(0);
        assertEquals("work", work.metadata().name());
        assertTrue(work.metadata().description().contains("替玩家干活"));
        assertTrue(work.body().contains("references/tiers.md"), "the entry points at references");
        assertEquals(3, work.references().size());
        assertTrue(work.reference("references/tiers.md").contains("CONSERVATIVE"));
    }

    @Test
    void theAdapterProvidesProgressiveDisclosure() {
        DubheSkill skill = DubheSkillPackageResourceLoader.loadBuiltin().get(0);

        assertEquals("work", skill.id());
        assertTrue(skill.spec().description().contains("替玩家干活"));

        JsonObject body = skill.executor().execute(CONTEXT, new JsonObject()).join();
        assertEquals("OK", body.get("outcome").getAsString());
        assertTrue(body.get("body").getAsString().contains("决策顺序"));

        JsonObject args = new JsonObject();
        args.addProperty("reference", "references/tools.md");
        JsonObject reference = skill.executor().execute(CONTEXT, args).join();
        assertEquals("OK", reference.get("outcome").getAsString());
        assertTrue(reference.get("body").getAsString().contains("craft.recipe"));

        JsonObject missing = new JsonObject();
        missing.addProperty("reference", "references/nope.md");
        JsonObject failed = skill.executor().execute(CONTEXT, missing).join();
        assertEquals("FAILED", failed.get("outcome").getAsString());
        assertEquals("UNKNOWN_REFERENCE", failed.get("reason").getAsString());
    }

    @Test
    void packageSkillsRegisterLikeAnyOtherSkill() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        DubheSkillPackageResourceLoader.loadBuiltin().forEach(registry::register);

        assertTrue(registry.find("work").isPresent());
        assertEquals(1, registry.size());
        assertFalse(registry.find("work").orElseThrow().spec().description().isBlank());
    }
}