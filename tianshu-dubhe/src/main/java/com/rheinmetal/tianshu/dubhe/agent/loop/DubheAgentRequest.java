package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;

import java.util.List;

/**
 * One model request's inputs: the task, the transcript so far, and the skill directory.
 *
 * <p>The directory is the specs only; a model never sees executors or other skills' internals. Keeping the
 * request a value makes the loop testable with a fake model.</p>
 */
public record DubheAgentRequest(
        String taskId,
        String question,
        List<DubheAgentEntry> transcript,
        List<DubheSkillSpec> directory
) {

    public DubheAgentRequest {
        taskId = taskId == null ? "" : taskId.trim();
        question = question == null ? "" : question.trim();
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        directory = directory == null ? List.of() : List.copyOf(directory);
    }
}