package com.rheinmetal.tianshu.dubhe.agent.skill;

/**
 * Which lane a skill runs in.
 *
 * <p>{@link #PLAYER} work competes for the player-facing task slots; {@link #BACKGROUND} work (memory
 * consolidation and the like) never delays an answer and keeps its own budget.</p>
 */
public enum DubheSkillLane {

    PLAYER,
    BACKGROUND
}