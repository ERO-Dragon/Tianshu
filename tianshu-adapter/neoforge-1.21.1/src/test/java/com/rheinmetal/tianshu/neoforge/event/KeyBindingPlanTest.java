package com.rheinmetal.tianshu.neoforge.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ownership rule for declared keys: a default key code belongs to whoever declares it first, and a later
 * request is left unbound instead of silently taking it.
 *
 * <p>Kept free of Minecraft types on purpose — the rule is decided here, and the platform layer only turns the
 * decision into a mapping.</p>
 */
final class KeyBindingPlanTest {

    private static final String CATEGORY = "key.categories.tianshu";
    private static final int KEY_H = 72;
    private static final int KEY_J = 74;

    @Test
    void theFirstDeclarationKeepsItsDefaultKey() {
        List<KeyBindingPlan.Entry> plan = KeyBindingPlan.resolve(List.of(
                new KeyBindingPlan.Declaration("key.first", KEY_H, CATEGORY)));

        assertEquals(1, plan.size());
        assertEquals(KEY_H, plan.get(0).keyCode());
        assertFalse(plan.get(0).unbound());
    }

    @Test
    void aSecondDeclarationOfTheSameDefaultIsLeftUnboundRatherThanStealingIt() {
        List<KeyBindingPlan.Entry> plan = KeyBindingPlan.resolve(List.of(
                new KeyBindingPlan.Declaration("key.first", KEY_H, CATEGORY),
                new KeyBindingPlan.Declaration("key.second", KEY_H, CATEGORY)));

        assertEquals(2, plan.size(), "both keys still exist, so the player can bind the loser by hand");
        assertEquals(KEY_H, plan.get(0).keyCode());
        assertTrue(plan.get(1).unbound());
        assertEquals(KeyBindingPlan.UNBOUND, plan.get(1).keyCode());
        assertEquals("key.second", plan.get(1).keyId());
    }

    @Test
    void distinctDefaultsBothKeepTheirCode() {
        List<KeyBindingPlan.Entry> plan = KeyBindingPlan.resolve(List.of(
                new KeyBindingPlan.Declaration("key.first", KEY_H, CATEGORY),
                new KeyBindingPlan.Declaration("key.second", KEY_J, CATEGORY)));

        assertFalse(plan.get(0).unbound());
        assertFalse(plan.get(1).unbound());
        assertEquals(KEY_J, plan.get(1).keyCode());
    }

    @Test
    void blankDeclarationsAreDropped() {
        List<KeyBindingPlan.Entry> plan = KeyBindingPlan.resolve(List.of(
                new KeyBindingPlan.Declaration("  ", KEY_H, CATEGORY),
                new KeyBindingPlan.Declaration(null, KEY_J, CATEGORY)));

        assertTrue(plan.isEmpty());
    }
}
