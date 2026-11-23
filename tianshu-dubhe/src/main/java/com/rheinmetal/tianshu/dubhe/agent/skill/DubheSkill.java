package com.rheinmetal.tianshu.dubhe.agent.skill;

/**
 * A registered skill: the data the agent reads, plus the code that runs it.
 *
 * <p>They are bundled deliberately. A registry of descriptions alone could not execute anything, and a separate
 * id → executor map would let the two sides drift; one registration keeps them consistent.</p>
 */
public record DubheSkill(DubheSkillSpec spec, DubheSkillExecutor executor) {

    public DubheSkill {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
    }

    public String id() {
        return spec.id();
    }
}