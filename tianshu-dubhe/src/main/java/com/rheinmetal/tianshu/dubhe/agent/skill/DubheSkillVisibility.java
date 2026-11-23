package com.rheinmetal.tianshu.dubhe.agent.skill;

/**
 * Who may see a skill.
 *
 * <p>{@link #INTERNAL} skills live only in this module: the agent reads them and nothing else can reach them.
 * {@link #EXTERNAL} skills are additionally registered as a 舟楫 {@code CapabilityKind.SKILL}, so other modules can
 * invoke them; that is why an external skill must declare its autonomy (G47).</p>
 */
public enum DubheSkillVisibility {

    INTERNAL,
    EXTERNAL
}