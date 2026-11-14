package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/**
 * The one place that answers "may this tool run in the current mode?".
 *
 * <p>The mode is a caller-side setting (default {@link ToolAutonomy#CONSERVATIVE}). It is not a confirmation
 * prompt: a tool declares the tier it needs, and the gate simply compares that with the mode. Server-side dispatch
 * applies the same comparison; operator and the per-tool switch remain the server's own boundary.</p>
 */
public final class ToolAutonomyGate {

    private static volatile ToolAutonomy mode = ToolAutonomy.CONSERVATIVE;

    private ToolAutonomyGate() {
    }

    public static ToolAutonomy mode() {
        return mode;
    }

    public static void setMode(ToolAutonomy newMode) {
        mode = Objects.requireNonNull(newMode, "mode");
    }

    /** True when the current mode permits a tool that declares {@code required}. */
    public static boolean permits(ToolAutonomy required) {
        return mode.permits(Objects.requireNonNull(required, "required"));
    }

    /** Tests only. */
    public static void reset() {
        mode = ToolAutonomy.CONSERVATIVE;
    }
}
