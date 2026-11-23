package com.rheinmetal.tianshu.dubhe.agent.skill;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Runs one skill invocation.
 *
 * <p>Returns the skill's own result object; the agent folds it into the task's report. Implementations must not
 * block the caller — a skill that waits on another module returns a future that completes later.</p>
 *
 * <p>The executor is the only place a skill touches the outside world (stores, protocol, tools), which keeps
 * {@link DubheSkillSpec} pure data and the agent loop free of per-skill branches.</p>
 */
@FunctionalInterface
public interface DubheSkillExecutor {

    CompletableFuture<JsonObject> execute(DubheSkillContext context, JsonObject args);
}