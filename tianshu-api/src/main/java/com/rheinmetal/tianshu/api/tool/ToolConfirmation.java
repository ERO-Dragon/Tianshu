package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Asks the player to approve one write. The platform installs an implementation (a screen, a panel, a test
 * double); the tool layer never draws anything itself.
 *
 * <p>With no implementation installed, a confirmation is denied: a missing UI must not silently grant world
 * changes.</p>
 */
@FunctionalInterface
public interface ToolConfirmation {

    /** What the player is being asked to approve; enough to show a precise card. */
    record Attempt(ToolId toolId, ToolCategory category, String description, JsonObject args, ToolCaller caller) {

        public Attempt {
            Objects.requireNonNull(toolId, "toolId");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(caller, "caller");
        }
    }

    CompletableFuture<Boolean> confirm(Attempt attempt);
}
