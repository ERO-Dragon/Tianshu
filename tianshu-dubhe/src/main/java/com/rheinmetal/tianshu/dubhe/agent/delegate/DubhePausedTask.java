package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentDigest;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentEntry;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

import java.util.List;

/**
 * A task stopped at a resumable point, waiting for the player to allow more work (G48).
 *
 * <p>It holds the transcript so a resume continues instead of starting over; the digest travels with it because
 * compaction may have already replaced some rounds. Paused tasks are session-scoped and short-lived: a new
 * delegation that is not an agreement drops them.</p>
 *
 * @param taskId       the ticket that paused
 * @param sessionId    the conversation the player must answer in
 * @param scope        where the work ran
 * @param question     the original delegated question, so a resume does not need it again
 * @param transcript   the rounds so far
 * @param digest       the progress digest, when compaction already ran
 * @param resumes      how many continuations this task has already used
 * @param createdAtMillis when it paused
 */
public record DubhePausedTask(
        String taskId,
        String sessionId,
        DubheScope scope,
        String question,
        List<DubheAgentEntry> transcript,
        DubheAgentDigest digest,
        int resumes,
        long createdAtMillis
) {

    public DubhePausedTask {
        taskId = taskId == null ? "" : taskId.trim();
        sessionId = sessionId == null ? "" : sessionId;
        scope = scope == null ? DubheScope.unknown() : scope;
        question = question == null ? "" : question.trim();
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        if (resumes < 0) {
            throw new IllegalArgumentException("resumes must be >= 0");
        }
    }
}