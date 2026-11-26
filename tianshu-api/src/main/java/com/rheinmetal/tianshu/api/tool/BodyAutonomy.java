package com.rheinmetal.tianshu.api.tool;

/** The two action tiers for a module-controlled NPC. */
public enum BodyAutonomy implements AutonomyRequirement {
    SURVIVAL, CHEAT;

    public boolean permits(BodyAutonomy required) {
        return required == SURVIVAL || (required == CHEAT && this == CHEAT);
    }

    public static BodyAutonomy parse(String configured) {
        return "cheat".equalsIgnoreCase(configured == null ? "" : configured.trim()) ? CHEAT : SURVIVAL;
    }
}
