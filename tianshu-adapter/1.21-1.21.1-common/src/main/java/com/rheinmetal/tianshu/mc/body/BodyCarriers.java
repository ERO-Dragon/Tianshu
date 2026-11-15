package com.rheinmetal.tianshu.mc.body;

import net.minecraft.world.entity.EntityType;

/**
 * The entity types this adapter registered, handed to the body registry after loader registration runs.
 *
 * <p>Registration is per loader, so the common side cannot create the type itself; it receives it here instead of
 * reaching into a loader registry from game code.</p>
 */
public final class BodyCarriers {

    private static EntityType<WalkerBody> walker;

    private BodyCarriers() {
    }

    public static void installWalker(EntityType<WalkerBody> type) {
        walker = type;
    }

    public static EntityType<WalkerBody> walker() {
        if (walker == null) {
            throw new IllegalStateException("the walker entity type is not registered");
        }
        return walker;
    }
}