package com.rheinmetal.tianshu.api.entity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTypesAndBodiesTest {

    @AfterEach
    void clean() {
        EntityTypes.resetForTests();
        EntityBodies.uninstall();
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

    @Test
    void aBodyNeedsATypeOnlyWhenItIsNotPlayerLike() {
        new EntityBodySpec("lab:orb-1", "lab:orb", false);
        new EntityBodySpec("lab:npc-1", null, true);

        assertThrows(NullPointerException.class, () -> new EntityBodySpec("lab:orb-1", null, false));
        assertThrows(IllegalArgumentException.class, () -> new EntityBodySpec("orb", "lab:orb", false));
    }

    @Test
    void creatingABodyGoesThroughTheInstalledPort() {
        EntityBodySpec spec = new EntityBodySpec("lab:orb-1", "lab:orb", false);
        EntityActor actor = actor("lab:orb-1", false);
        EntityBodies.install(bodySpec -> Optional.of(actor));

        assertTrue(EntityBodies.available());
        assertEquals(Optional.of(actor), EntityBodies.create(spec));
    }

    @Test
    void thePortCarriesThePlayerLikeFlagSoTheAdapterCanPickTheBase() {
        boolean[] seen = new boolean[1];
        EntityBodies.install(bodySpec -> {
            seen[0] = bodySpec.playerLike();
            return Optional.of(actor(bodySpec.bodyId(), bodySpec.playerLike()));
        });

        EntityActor playerLike = EntityBodies.create(new EntityBodySpec("lab:npc-1", null, true)).orElseThrow();
        EntityActor ordinary = EntityBodies
                .create(new EntityBodySpec("lab:orb-1", "lab:orb", false)).orElseThrow();

        assertTrue(seen[0] == false, "the flag travels with the request");
        assertTrue(playerLike.playerLike());
        assertFalse(ordinary.playerLike());
    }

    @Test
    void askingWithoutAPortIsAWiringMistake() {
        EntityBodies.uninstall();

        assertFalse(EntityBodies.available());
        assertThrows(IllegalStateException.class,
                () -> EntityBodies.create(new EntityBodySpec("lab:orb-1", "lab:orb", false)));
    }

    private static EntityActor actor(String id, boolean playerLike) {
        return new EntityActor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean operator() {
                return false;
            }

            @Override
            public boolean playerLike() {
                return playerLike;
            }

            @Override
            public ActorPosition position() {
                return new ActorPosition("minecraft:overworld", 0, 64, 0);
            }

            @Override
            public java.util.Set<EntityCapability> capabilities() {
                return java.util.EnumSet.of(EntityCapability.LOOK);
            }
        };
    }
}