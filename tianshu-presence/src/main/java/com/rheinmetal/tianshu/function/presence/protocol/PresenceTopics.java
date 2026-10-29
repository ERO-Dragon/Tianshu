package com.rheinmetal.tianshu.function.presence.protocol;

/**
 * The topics presence owns.
 *
 * <p>They live with the module that owns them: the kernel's routing table only needs the platform's own topics, and a
 * module registers its topics when it contributes to a session. That also means an absent presence registers nothing
 * and a producer's bridge simply does not publish, instead of the kernel keeping a topic alive for a feature that is
 * not installed.</p>
 */
public final class PresenceTopics {

    /** Product activity reported by every module that wants the HUD to show it. */
    public static final String ACTIVITY = "PRESENCE.ACTIVITY";
    /** The claim-relevant interaction summary presence pushes when it changes. */
    public static final String INTERACTION_CONTEXT = "PRESENCE.INTERACTION_CONTEXT";
    /** The pre-winner or winner the arbiter writes and presence renders. */
    public static final String PARTNER = "PRESENCE.PARTNER";
    /** The aggregated windows presence re-publishes for consumers (AX memory, diagnostics). */
    public static final String WORLD_EVENT = "PRESENCE.WORLD_EVENT";

    private PresenceTopics() {
    }
}
