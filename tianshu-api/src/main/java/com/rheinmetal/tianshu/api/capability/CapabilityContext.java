package com.rheinmetal.tianshu.api.capability;

import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.Objects;

/** What a capability handler is allowed to know about one call. */
public record CapabilityContext(ToolCaller caller, ToolSide side) {

    public CapabilityContext {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(side, "side");
    }
}
