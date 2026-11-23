package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.google.gson.JsonObject;

/**
 * The model asking the agent to run one skill.
 *
 * @param skillId   the id the model read from the directory; unknown ids are tolerated and reported back
 * @param arguments the arguments, validated against the skill's schema by the model-side prompt
 */
public record DubheSkillCall(String skillId, JsonObject arguments) {

    public DubheSkillCall {
        skillId = skillId == null ? "" : skillId.trim();
        if (skillId.isEmpty()) {
            throw new IllegalArgumentException("skillId must not be blank");
        }
        arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
    }
}