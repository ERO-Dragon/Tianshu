package com.rheinmetal.tianshu.client.event;

/**
 * Kinds this capture layer produces.
 *
 * <p>Only the subset that can be measured deterministically from the local player each sample is
 * listed here; the tier table still owns their tier and blacklist flag, so adding a producer never
 * moves policy into code.
 */
public final class AXGameEventKinds {
    public static final String DEATH = "tianshu:death";
    public static final String RESPAWN = "tianshu:respawn";
    public static final String HEALTH_CRITICAL = "tianshu:health_critical";
    public static final String HEALTH_CHANGE = "tianshu:health_change";
    public static final String HUNGER_CRITICAL = "tianshu:hunger_critical";
    public static final String HUNGER_CHANGE = "tianshu:hunger_change";
    public static final String DIMENSION_ENTER = "tianshu:dimension_enter";
    public static final String BIOME_CHANGE = "tianshu:biome_change";

    private AXGameEventKinds() {
    }
}
