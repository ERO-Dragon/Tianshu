package com.rheinmetal.tianshu.mc.body;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * A walking carrier: a pathfinder mob with no AI goals of its own.
 *
 * <p>The platform issues movement, so this entity deliberately registers no goals and never despawns on its own.
 * It exists to give a body a real body to walk with — the navigation it inherits is vanilla's, not a hand-rolled
 * approximation.</p>
 */
public class WalkerBody extends PathfinderMob {

    public WalkerBody(EntityType<? extends WalkerBody> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        // No goals: movement comes from the body port, not from mob AI.
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /** The attribute set a walking carrier needs; speed is the vanilla walking baseline. */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.STEP_HEIGHT, 0.6D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }
}