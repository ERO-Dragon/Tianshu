package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/**
 * Everything a tool implementation is allowed to know about one call.
 *
 * <p>It carries no game objects on purpose: a tool that needs live game state captures its own handle when it
 * registers, which keeps this type usable from the platform-neutral layer. The context exists so a tool can
 * adapt its result to the caller and the side, not so it can reach around the ports.</p>
 */
public record ToolContext(ToolCaller caller, ToolSide side, CancellationSignal cancellation) {

    public ToolContext {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(side, "side");
        cancellation = cancellation == null ? CancellationSignal.NEVER : cancellation;
    }

    public ToolContext(ToolCaller caller, ToolSide side) {
        this(caller, side, CancellationSignal.NEVER);
    }
}