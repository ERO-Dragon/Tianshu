package com.rheinmetal.tianshu.dubhe.agent.skill;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSkillRegistryTest {

    private static final DubheSkillExecutor NOOP = (context, args) ->
            CompletableFuture.completedFuture(new JsonObject());

    private static DubheSkill skill(String id) {
        DubheSkillSpec spec = DubheSkillSpec.builder(id, "做一个 " + id + " 测试")
                .budget(DubheSkillBudget.of(5_000L, 1))
                .build();
        return new DubheSkill(spec, NOOP);
    }

    @Test
    void anEmptyRegistryIsALegalState() {
        DubheSkillRegistry registry = new DubheSkillRegistry();

        assertTrue(registry.isEmpty());
        assertEquals(0, registry.size());
        assertEquals(List.of(), registry.all());
        assertTrue(registry.find("anything").isEmpty());
        assertEquals(List.of(), registry.resolve(List.of("anything")));
    }

    @Test
    void aRegisteredSkillIsFoundWithItsExecutor() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(skill("research"));

        DubheSkill found = registry.find("research").orElseThrow();
        assertEquals("research", found.id());
        assertEquals(NOOP, found.executor());
        assertEquals(1, registry.size());
        assertFalse(registry.isEmpty());
    }

    @Test
    void unknownIdsAreToleratedNotRejected() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(skill("research"));

        assertTrue(registry.find("retired.skill").isEmpty());
        assertTrue(registry.find("").isEmpty());
        assertTrue(registry.find(null).isEmpty());
        assertEquals(List.of("research"),
                registry.resolve(List.of("retired.skill", "research")).stream()
                        .map(DubheSkill::id).toList());
    }

    @Test
    void twoSkillsMayNotAnswerToOneId() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(skill("research"));

        assertThrows(IllegalArgumentException.class, () -> registry.register(skill("research")));
    }

    @Test
    void theDirectoryKeepsRegistrationOrderSoTheAgentPromptIsStable() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(skill("memory"));
        registry.register(skill("research"));
        registry.register(skill("diplomacy"));

        assertEquals(List.of("memory", "research", "diplomacy"),
                registry.all().stream().map(DubheSkill::id).toList());
    }

    @Test
    void aSkillNeedsBothItsDescriptionAndItsExecutor() {
        DubheSkillSpec spec = skill("research").spec();
        assertThrows(IllegalArgumentException.class, () -> new DubheSkill(null, NOOP));
        assertThrows(IllegalArgumentException.class, () -> new DubheSkill(spec, null));
    }

    @Test
    void clearReturnsToTheEmptyState() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(skill("research"));
        registry.clear();

        assertTrue(registry.isEmpty());
        assertTrue(registry.find("research").isEmpty());
    }
}