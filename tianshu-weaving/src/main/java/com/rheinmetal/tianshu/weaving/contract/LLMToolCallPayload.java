package com.rheinmetal.tianshu.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * One tool call the model asked for, or one it asked for earlier in the conversation.
 *
 * <p>The same record serves both directions: a terminal result carries the calls the model just
 * requested, and an assistant message replays those calls back in the next request, which is what the
 * provider's protocol requires before a tool result may follow. Weaving never executes a call and
 * never decides which one to make.</p>
 *
 * @param id            provider-assigned call id; a tool result references it
 * @param name          function name the model chose
 * @param argumentsJson the arguments as the model emitted them, as text
 */
public record LLMToolCallPayload(
        String id,
        String name,
        String argumentsJson
) implements ITianshuPayload {

    public LLMToolCallPayload {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson.trim();
    }
}