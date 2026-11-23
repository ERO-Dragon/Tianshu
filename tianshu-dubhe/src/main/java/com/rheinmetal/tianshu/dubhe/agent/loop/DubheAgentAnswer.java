package com.rheinmetal.tianshu.dubhe.agent.loop;

import java.util.List;

/**
 * The agent's final answer for one task, before it is turned into a report.
 *
 * @param text       what the agent concluded; may be blank when it only collected facts
 * @param usedSkills skill ids actually invoked, in order, deduplicated; empty means the agent answered alone
 */
public record DubheAgentAnswer(String text, List<String> usedSkills) {

    public DubheAgentAnswer {
        text = text == null ? "" : text.trim();
        usedSkills = usedSkills == null ? List.of() : List.copyOf(usedSkills);
    }
}