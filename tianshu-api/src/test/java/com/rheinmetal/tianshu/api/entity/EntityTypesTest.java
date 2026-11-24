package com.rheinmetal.tianshu.api.entity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTypesTest {

    @AfterEach
    void clean() {
        EntityTypes.resetForTests();
    }

    @Test
    void theSameDeclarationTwiceIsAcceptedOnce() {
        EntityTypeSpec spec = new EntityTypeSpec("lab", "orb", 0.5F, 0.5F, true);

        assertEquals(spec, EntityTypes.declare(spec));
        assertEquals(spec, EntityTypes.declare(new EntityTypeSpec("lab", "orb", 0.5F, 0.5F, true)));
        assertEquals(1, EntityTypes.declared().size());
    }

    @Test
    void aDifferentShapeUnderOneIdIsRefused() {
        EntityTypes.declare(new EntityTypeSpec("lab", "orb", 0.5F, 0.5F, false));

        assertThrows(IllegalArgumentException.class,
                () -> EntityTypes.declare(new EntityTypeSpec("lab", "orb", 1.0F, 1.0F, false)));
    }

    @Test
    void aDeclarationAfterRegistrationIsRefusedRatherThanSilentlyDropped() {
        EntityTypes.declare(new EntityTypeSpec("lab", "orb", 0.5F, 0.5F, false));
        List<EntityTypeSpec> drained = EntityTypes.drainForRegistration();

        assertEquals(1, drained.size());
        assertTrue(EntityTypes.closed());
        assertThrows(IllegalStateException.class,
                () -> EntityTypes.declare(new EntityTypeSpec("lab", "late", 0.5F, 0.5F, false)));
    }

    @Test
    void theIdentifierIsNamespacedAndNormalised() {
        EntityTypeSpec spec = new EntityTypeSpec("Lab", "Floating_Orb", 1.0F, 1.0F, false);

        assertEquals("lab:floating_orb", spec.id());
        assertThrows(IllegalArgumentException.class,
                () -> new EntityTypeSpec("lab", "bad name", 1.0F, 1.0F, false));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityTypeSpec("lab", "orb", 0.0F, 1.0F, false));
    }

    /**
     * Spawning a declared type is an ownership question, and the namespace answers it: the module that declares a
     * type is the only one allowed to ask for it.
     */
    @Test
    void aDeclaredTypeIsOwnedByTheModuleItsNamespaceNames() {
        assertTrue(EntityTypes.ownedBy("lab", "lab:orb"));
        assertTrue(EntityTypes.ownedBy(" Lab ", "lab:orb"), "the declaration normalises the id, so the rule does too");
        assertFalse(EntityTypes.ownedBy("dubhe", "lab:orb"));
        assertFalse(EntityTypes.ownedBy("lab", "labx:orb"), "a prefix is not a namespace");
        assertFalse(EntityTypes.ownedBy("lab", "orb"), "an unnamespaced id belongs to nobody");
        assertFalse(EntityTypes.ownedBy(null, "lab:orb"));
        assertFalse(EntityTypes.ownedBy("", "lab:orb"));
    }
}