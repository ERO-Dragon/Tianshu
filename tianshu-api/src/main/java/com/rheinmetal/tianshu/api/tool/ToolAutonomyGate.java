package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The one place that answers "may this write run without asking?".
 *
 * <p>The mode is a client-facing setting (default {@link ToolAutonomy#CONFIRM}); the confirmation implementation is
 * supplied by the platform. Server-side dispatch does not ask the player again — it is already operator-gated and
 * the request only exists because the client approved it.</p>
 */
public final class ToolAutonomyGate {

    private static volatile ToolAutonomy mode = ToolAutonomy.CONFIRM;
    private static volatile ToolConfirmation confirmation;

    private ToolAutonomyGate() {
    }

    public static ToolAutonomy mode() {
        return mode;
    }

    public static void setMode(ToolAutonomy newMode) {
        mode = Objects.requireNonNull(newMode, "mode");
    }

    public static void installConfirmation(ToolConfirmation implementation) {
        confirmation = Objects.requireNonNull(implementation, "implementation");
    }

    public static void uninstallConfirmation() {
        confirmation = null;
    }

    /** A write tool is visible to a caller only when the mode permits writing. */
    public static boolean writeToolsVisible() {
        return mode.mayWrite();
    }

    /** True when this call must be confirmed before it runs: a write reaching the server from the client. */
    public static boolean needsConfirmation(ToolCategory category, ToolSide side) {
        return side == ToolSide.CLIENT && category == ToolCategory.SERVER_WRITE && mode == ToolAutonomy.CONFIRM;
    }

    /** No implementation means denied, never approved. */
    public static CompletableFuture<Boolean> confirm(ToolId id, ToolCategory category, String description,
                                                     JsonObject args, ToolCaller caller) {
        ToolConfirmation implementation = confirmation;
        if (implementation == null) {
            return CompletableFuture.completedFuture(false);
        }
        return implementation.confirm(new ToolConfirmation.Attempt(id, category, description, args, caller));
    }

    /** Tests only. */
    public static void reset() {
        mode = ToolAutonomy.CONFIRM;
        confirmation = null;
    }
}
