package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Runs one parsed call through the registry.
 *
 * <p>Errors are values here, exactly as the registry reports them for the model: the agent gets
 * {@code {"ok":false,"code":...}} and can decide whether to retry, ask the player, or explain that the server does
 * not offer the tool.</p>
 */
public final class AXToolExecutor {

    private final ToolCaller caller;

    public AXToolExecutor() {
        this(ToolCaller.agent(false));
    }

    public AXToolExecutor(ToolCaller caller) {
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    public CompletableFuture<JsonObject> call(AXToolCall call) {
        return TianshuTools.callJson(call.toolId(), call.args(), caller);
    }
}
