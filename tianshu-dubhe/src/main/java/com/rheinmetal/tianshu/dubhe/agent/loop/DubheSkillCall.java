package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.google.gson.JsonObject;

/**
 * The model asking the agent to run one skill, as received through native tool calling.
 *
 * @param id        provider-assigned call id, echoed back on the tool message that answers it
 * @param skillId   the function name the model chose; unknown names are tolerated and reported back
 * @param arguments the arguments, already parsed from the provider's JSON text
 */
public record DubheSkillCall(String id, String skillId, JsonObject arguments) {

    public DubheSkillCall {
        id = id == null ? "" : id.trim();
        skillId = skillId == null ? "" : skillId.trim();
        if (skillId.isEmpty()) {
            throw new IllegalArgumentException("skillId must not be blank");
        }
        arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
    }
}