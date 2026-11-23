package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;

import java.util.List;

/**
 * One model request's inputs: the task, the transcript so far, the skill directory, and the step's budget.
 *
 * <p>The directory is the specs only; a model never sees executors or other skills' internals. The budget and the
 * cancellation probe travel with the request so the transport adapter can bound its wait without reaching into
 * the loop.</p>
 */
public record DubheAgentRequest(
        String taskId,
        String question,
        List<DubheAgentEntry> transcript,
        List<DubheSkillSpec> directory,
        long remainingMillis,
        DubheRunCancellation cancellation
) {

    public DubheAgentRequest {
        taskId = taskId == null ? "" : taskId.trim();
        question = question == null ? "" : question.trim();
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        directory = directory == null ? List.of() : List.copyOf(directory);
        if (remainingMillis <= 0L) {
            throw new IllegalArgumentException("remainingMillis must be positive");
        }
        cancellation = cancellation == null ? DubheRunCancellation.none() : cancellation;
    }
}