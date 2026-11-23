package com.rheinmetal.tianshu.dubhe.agent.skill;

import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

/**
 * What a skill gets to see when the agent invokes it.
 *
 * <p>Deliberately small: the delegated question, the ticket it belongs to, and the scope the work runs in.
 * Anything else a skill needs (memory store, protocol adapter, tools) is injected into its executor when that
 * skill is registered, not handed over per call.</p>
 *
 * @param taskId   the ticket id, so a skill can join its own diagnostics with the delivery ledger
 * @param question the delegated question, already stripped of the {@code <delegate>} tags
 * @param scope    the player/world scope the task belongs to
 */
public record DubheSkillContext(String taskId, String question, DubheScope scope) {

    public DubheSkillContext {
        taskId = taskId == null ? "" : taskId.trim();
        question = question == null ? "" : question.trim();
        scope = scope == null ? DubheScope.unknown() : scope;
    }
}