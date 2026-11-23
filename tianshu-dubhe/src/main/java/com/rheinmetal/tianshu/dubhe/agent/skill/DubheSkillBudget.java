package com.rheinmetal.tianshu.dubhe.agent.skill;

/**
 * A skill's own ceiling for one invocation.
 *
 * <p>Budgets are declared per skill rather than globally (G46): a lookup may take seconds, while a memory pass can
 * take longer. The agent loop still enforces its whole-task step and wall-clock limits.</p>
 *
 * @param timeoutMillis hard ceiling for one invocation, milliseconds
 * @param maxSteps      model steps this skill may take
 */
public record DubheSkillBudget(long timeoutMillis, int maxSteps) {

    public DubheSkillBudget {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }

    public static DubheSkillBudget of(long timeoutMillis, int maxSteps) {
        return new DubheSkillBudget(timeoutMillis, maxSteps);
    }
}