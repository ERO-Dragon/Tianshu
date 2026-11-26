package com.rheinmetal.tianshu.api.capability;

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

    /** The platform document reader resolves each request against the caller's subscribed rows. */
    public static final CapabilityId DOCUMENT_READER = CapabilityId.of("minecraft", "capability.read");

    private CapabilityAccess() {
    }

    /** Players and bodies have their own permission checks. Modules and agents consume as their module id. */
    public static boolean visibleTo(CapabilityDescriptor descriptor, ToolCaller caller) {
        return visibleTo(descriptor, caller, CapabilitySubscriptions.ruleFor(caller.id()));
    }

    static boolean visibleTo(CapabilityDescriptor descriptor, ToolCaller caller, CapabilitySubscription rule) {
        return switch (caller.kind()) {
            case PLAYER, BODY -> true;
            case MOD, AGENT -> caller.id().equals(descriptor.provider())
                    || CapabilitySharing.of(descriptor).permits(caller.id())
                    && (isDocumentReader(descriptor) || rule.accepts(descriptor));
        };
    }

    private static boolean isDocumentReader(CapabilityDescriptor row) {
        return row.id().equals(DOCUMENT_READER) && row.kind().isTool()
                && CapabilitySharing.BASE_PROVIDER.equals(row.provider());
    }
}
