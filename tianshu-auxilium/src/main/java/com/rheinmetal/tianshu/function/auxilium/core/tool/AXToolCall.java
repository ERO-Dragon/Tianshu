package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolId;

/**
 * One tool call the model asked for: the id and the argument object it produced.
 *
 * <p>Parsing is strict on purpose: a reply either is exactly one call or it is an ordinary answer. A half-parsed
 * call is dropped rather than guessed, because a wrong tool call costs a round trip and can act on the world.</p>
 */
public record AXToolCall(ToolId toolId, JsonObject args) {
}
