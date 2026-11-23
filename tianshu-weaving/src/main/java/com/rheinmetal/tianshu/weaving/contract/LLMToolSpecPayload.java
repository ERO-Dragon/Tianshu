package com.rheinmetal.tianshu.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * One tool the caller offers the model, in the shape the provider's own protocol wants.
 *
 * <p>This is transport, not orchestration: the caller owns the catalogue (which tools exist), the
 * permission to use them and their execution. Weaving only carries the declaration down and the
 * model's answer back — see {@link LLMToolCallPayload}. The scheduler, the authorization model and
 * the result rendering stay with the caller, which is why nothing here names a game concept.</p>
 *
 * @param name           function name the model must answer with; trimmed, required
 * @param description    what the tool does, in the caller's own words; may be empty
 * @param parametersJson JSON Schema object of the arguments as text
 */
public record LLMToolSpecPayload(
        String name,
        String description,
        String parametersJson
) implements ITianshuPayload {

    /** A schema for a tool that takes no arguments; what a blank {@code parametersJson} means. */
    public static final String NO_ARGUMENTS_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    public LLMToolSpecPayload {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description;
        parametersJson = parametersJson == null || parametersJson.isBlank()
                ? NO_ARGUMENTS_SCHEMA
                : parametersJson.trim();
    }

    /** A tool that takes no arguments. */
    public static LLMToolSpecPayload noArguments(String name, String description) {
        return new LLMToolSpecPayload(name, description, NO_ARGUMENTS_SCHEMA);
    }
}