package com.rheinmetal.tianshu.api.tool;

/** Minimum autonomy for a tool: a player tier, an NPC tier, or a query with no action tier. */
public sealed interface AutonomyRequirement permits ToolAutonomy, BodyAutonomy, AutonomyRequirement.None {

    enum None implements AutonomyRequirement {
        INSTANCE
    }
}
