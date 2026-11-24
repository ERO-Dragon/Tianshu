package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillRegistry;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSkillPackageResourceTest {

    private static final DubheSkillContext CONTEXT = new DubheSkillContext("task-1", "帮我把木头搬进箱子",
            new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true));

    @Test
    void bothPackagesLoadWithTheirOwnReferences() {
        Map<String, DubheSkillDefinition> byName = byName();

        assertEquals(2, byName.size(), "work and diplomacy");

        DubheSkillDefinition work = byName.get("work");
        assertTrue(work.metadata().description().contains("tianshu:minecraft-tools"));
        assertTrue(work.body().contains("references/boundaries.md"), "the entry points at references");
        assertEquals(1, work.references().size());
        assertTrue(work.reference("references/boundaries.md").contains("失败"));
        assertTrue(work.body().contains("tianshu:minecraft-tools"), "tool usage lives in the platform skill");

        DubheSkillDefinition diplomacy = byName.get("diplomacy");
        assertEquals(6, diplomacy.references().size(), "discovery / relay / progress / permissions / failures / examples");
        assertTrue(diplomacy.body().contains("references/relay.md"), "relay is a reference of diplomacy");
        assertTrue(diplomacy.reference("references/relay.md").contains("taskId"),
                "the relay reference explains the acceptance receipt");
    }

    @Test
    void theAdapterProvidesProgressiveDisclosure() {
        DubheSkill skill = skill("work");

        assertEquals("work", skill.id());
        assertTrue(skill.spec().description().contains("替玩家干活"));

        JsonObject body = skill.executor().execute(CONTEXT, new JsonObject()).join();
        assertEquals("OK", body.get("outcome").getAsString());
        assertTrue(body.get("body").getAsString().contains("决策顺序"));

        JsonObject args = new JsonObject();
        args.addProperty("reference", "references/boundaries.md");
        JsonObject reference = skill.executor().execute(CONTEXT, args).join();
        assertEquals("OK", reference.get("outcome").getAsString());
        assertTrue(reference.get("body").getAsString().contains("游戏里的原因"));

        JsonObject missing = new JsonObject();
        missing.addProperty("reference", "references/nope.md");
        JsonObject failed = skill.executor().execute(CONTEXT, missing).join();
        assertEquals("FAILED", failed.get("outcome").getAsString());
        assertEquals("UNKNOWN_REFERENCE", failed.get("reason").getAsString());
    }

    @Test
    void theDiplomacyPackageReadsItsRelayReferenceOnDemand() {
        DubheSkill diplomacy = skill("diplomacy");

        JsonObject entry = diplomacy.executor().execute(CONTEXT, new JsonObject()).join();
        assertTrue(entry.get("body").getAsString().contains("四步"), "the entry carries the procedure");

        JsonObject args = new JsonObject();
        args.addProperty("reference", "references/relay.md");
        JsonObject reference = diplomacy.executor().execute(CONTEXT, args).join();
        assertEquals("OK", reference.get("outcome").getAsString());
        assertTrue(reference.get("body").getAsString().contains("不重复投递同一件事"));
    }

    @Test
    void packageSkillsRegisterLikeAnyOtherSkill() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        DubheSkillPackageResourceLoader.loadBuiltin().forEach(registry::register);

        assertTrue(registry.find("work").isPresent());
        assertTrue(registry.find("diplomacy").isPresent());
        assertEquals(2, registry.size());
        assertFalse(registry.find("work").orElseThrow().spec().description().isBlank());
    }

    private static Map<String, DubheSkillDefinition> byName() {
        return DubheSkillPackageResourceLoader.loadBuiltinDefinitions().stream()
                .collect(Collectors.toMap(definition -> definition.metadata().name(), Function.identity()));
    }

    private static DubheSkill skill(String name) {
        List<DubheSkill> skills = DubheSkillPackageResourceLoader.loadBuiltin();
        return skills.stream().filter(skill -> skill.id().equals(name)).findFirst().orElseThrow();
    }
}