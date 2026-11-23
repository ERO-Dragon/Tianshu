package com.rheinmetal.tianshu.dubhe.agent.loop;

import java.util.List;

/**
 * The agent's final answer for one task: the report simple may see, plus the skills that actually ran.
 *
 * @param report     what to speak and append; three fields, no enum
 * @param usedSkills skill ids invoked, in order, deduplicated; diagnostics only
 */
public record DubheAgentAnswer(DubheAgentReport report, List<String> usedSkills) {

    public DubheAgentAnswer {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        usedSkills = usedSkills == null ? List.of() : List.copyOf(usedSkills);
    }
}