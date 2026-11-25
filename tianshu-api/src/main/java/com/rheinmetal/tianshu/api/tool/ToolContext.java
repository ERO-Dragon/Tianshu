package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/**
 * Everything a tool implementation is allowed to know about one call.
 *
 * <p>It carries no game objects on purpose: a tool that needs live game state captures its own handle when it
 * registers, which keeps this type usable from the platform-neutral layer. The context exists so a tool can
 * adapt its result to the caller and the side, not so it can reach around the ports. The cancellation signal
 * is cooperative: a tool that never reads it still finishes normally.</p>
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

    /**
     * How much this call may do through a body, derived from the same ceiling the registry enforces.
     *
     * <p>For tools that are one thing overall but have a privileged sub-mode: a move that walks is survival work, the
     * same call that flies is not. Those tools check the sub-mode themselves against this value; everything else is
     * covered by the registry's comparison of {@link TianshuTool#requiredLevel()}.</p>
     */
    public BodyAutonomy allowedBodyAutonomy() {
        return BodyAutonomy.from(TianshuTools.autonomyFor(side, caller));
    }
}