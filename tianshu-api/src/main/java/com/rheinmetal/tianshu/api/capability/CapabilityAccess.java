package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonElement;
import com.rheinmetal.tianshu.api.tool.ToolCaller;

/**
 * The single rule for "may this consumer see this row": the provider opened it, and the consumer asked for it.
 *
 * <p>Both halves are required, and both are declarations rather than behaviour. A provider opening a capability to a
 * module that never subscribed leaves it invisible; a module subscribing to a provider that shares nothing gets nothing.
 * Keeping the two checks here — one place for the exposure list and the tool list — is what stops those two views from
 * disagreeing about what a module can see.</p>
 */
public final class CapabilityAccess {

    private CapabilityAccess() {
    }

    /** Whether {@code caller} may see this row. */
    public static boolean visibleTo(CapabilityDescriptor descriptor, ToolCaller caller) {
        String consumer = consumerModuleOf(caller);
        if (consumer == null) {
            // A player or a body is not a module: sharing is a module-to-module arrangement, so it does not narrow a
            // human's own session. What bounds that is the tier and the permission, checked by the exposure filter.
            return true;
        }
        if (consumer.equals(descriptor.provider())) {
            return true;
        }
        if (!CapabilitySharing.of(descriptor).permits(consumer)) {
            return false;
        }
        // The row goes in whole: the default subscription accepts "whatever its provider opened", and that can only be
        // answered from the row itself.
        return CapabilitySubscriptions.ruleFor(consumer).accepts(descriptor);
    }

    /**
     * The same rule for a tool: its row may not exist yet, and a tool with no row is one the kernel itself just
     * published, so it stays visible. The exposure list is where an unmirrored row simply does not appear.
     */
    public static boolean visibleToolTo(CapabilityDescriptor row, ToolCaller caller) {
        return row == null || visibleTo(row, caller);
    }

    /**
     * The rule itself, for callers that know a provider and kind before a row exists.
     *
     * <p>{@code declaredShare} is the provider's declaration in attribute form; passing null asks for the default that
     * belongs to that provider, which is "open" for the base and "private" for a module.</p>
     */
    public static boolean visibleTo(String provider, CapabilityKind kind, JsonElement declaredShare, String consumer) {
        if (consumer == null || consumer.isBlank()) {
            return true;
        }
        if (consumer.equals(provider)) {
            return true;
        }
        if (!CapabilitySharing.ofProvider(provider, declaredShare).permits(consumer)) {
            return false;
        }
        return CapabilitySubscriptions.ruleFor(consumer).accepts(provider, kind);
    }

    /** The module id behind a caller, or null when the caller is a player or a body rather than a module. */
    public static String consumerModuleOf(ToolCaller caller) {
        return switch (caller.kind()) {
            case MOD, AGENT -> caller.id() == null || caller.id().isBlank() ? null : caller.id().trim();
            case PLAYER, BODY -> null;
        };
    }
}