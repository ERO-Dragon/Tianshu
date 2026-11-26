package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/**
 * When a tool may run, how long it may take and who may call it.
 *
 * <p>The tier decides the policy, not the category: only a tool that needs {@link ToolAutonomy#FULL} is a power an
 * operator hands over deliberately, so only that one starts switched off and gated behind operator. A tool that stays
 * inside survival is exactly the crafting and container work a player does by hand — it needs no switch, and a module
 * that declares such a tool gets that default without having to remember an override.</p>
 */
public record ToolPolicy(boolean enabledByDefault, ToolPermission permission, long timeoutMillis) {

    public static final long DEFAULT_TIMEOUT_MILLIS = 2_000L;

    public ToolPolicy {
        Objects.requireNonNull(permission, "permission");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("a tool timeout must be positive: " + timeoutMillis);
        }
    }

    /** The policy a tool needing this tier gets unless it overrides one. */
    public static ToolPolicy forTier(ToolAutonomy tier) {
        Objects.requireNonNull(tier, "tier");
        return tier == ToolAutonomy.FULL
                ? new ToolPolicy(false, ToolPermission.OPERATOR, DEFAULT_TIMEOUT_MILLIS)
                : new ToolPolicy(true, ToolPermission.EVERYONE, DEFAULT_TIMEOUT_MILLIS);
    }

    /** The policy a tool of this category gets; the category only supplies its default tier. */
    public static ToolPolicy forCategory(ToolCategory category) {
        Objects.requireNonNull(category, "category");
        return category.isBody() ? new ToolPolicy(true, ToolPermission.EVERYONE, DEFAULT_TIMEOUT_MILLIS)
                : forTier(category.defaultAutonomy());
    }

    public ToolPolicy withTimeout(long millis) {
        return new ToolPolicy(enabledByDefault, permission, millis);
    }

    public ToolPolicy withPermission(ToolPermission newPermission) {
        return new ToolPolicy(enabledByDefault, newPermission, timeoutMillis);
    }

    /**
     * Whether the tool is offered before anyone configures it.
     *
     * <p>A tool that only does what the player could do by hand is a reasonable default; a tool that changes the world
     * in ways the player could not is not. The category sets the default, and a tool may say it belongs on the other
     * side of that line — the survival actions do, because they are exactly a player's own crafting and container work,
     * still bounded by the player's rights and the server's claim rules.</p>
     */
    public ToolPolicy withEnabledByDefault(boolean enabled) {
        return new ToolPolicy(enabled, permission, timeoutMillis);
    }
}