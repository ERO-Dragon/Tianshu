package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/**
 * When a tool may run, how long it may take and who may call it.
 *
 * <p>The defaults follow the category, because that is the only classification the platform can trust without
 * knowing the tool: a local query is harmless, a server read is still harmless but needs the server component,
 * and a world change is opted into by an operator rather than granted by registration.</p>
 */
public record ToolPolicy(boolean enabledByDefault, ToolPermission permission, long timeoutMillis) {

    public static final long DEFAULT_TIMEOUT_MILLIS = 2_000L;

    public ToolPolicy {
        Objects.requireNonNull(permission, "permission");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("a tool timeout must be positive: " + timeoutMillis);
        }
    }

    /** The policy a tool of this category gets unless it overrides one. */
    public static ToolPolicy forCategory(ToolCategory category) {
        Objects.requireNonNull(category, "category");
        return switch (category) {
            case LOCAL, SERVER_READ -> new ToolPolicy(true, ToolPermission.EVERYONE, DEFAULT_TIMEOUT_MILLIS);
            case SERVER_WRITE -> new ToolPolicy(false, ToolPermission.OPERATOR, DEFAULT_TIMEOUT_MILLIS);
        };
    }

    public ToolPolicy withTimeout(long millis) {
        return new ToolPolicy(enabledByDefault, permission, millis);
    }

    public ToolPolicy withPermission(ToolPermission newPermission) {
        return new ToolPolicy(enabledByDefault, newPermission, timeoutMillis);
    }
}