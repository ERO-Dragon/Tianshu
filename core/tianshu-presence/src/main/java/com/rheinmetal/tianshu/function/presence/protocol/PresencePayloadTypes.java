package com.rheinmetal.tianshu.function.presence.protocol;

import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceInteractionContextPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.protocol.PayloadType;

/**
 * Presence's wire vocabulary.
 *
 * <p>The kernel keeps only the mechanism ({@link PayloadType#of(String, Class)}): a module introduces the payload
 * kinds it ships without editing the kernel. Binding the name to the class here also makes a mismatched producer fail
 * when the contract is first used instead of later at message validation.</p>
 */
public final class PresencePayloadTypes {

    public static final PayloadType ACTIVITY =
            PayloadType.of("PRESENCE_ACTIVITY", PresenceActivityPayload.class);
    public static final PayloadType CONTEXT_QUERY =
            PayloadType.of("PRESENCE_CONTEXT_QUERY", PresenceContextQueryPayload.class);
    public static final PayloadType CONTEXT_SNAPSHOT =
            PayloadType.of("PRESENCE_CONTEXT_SNAPSHOT", PresenceContextSnapshotPayload.class);
    public static final PayloadType INTERACTION_CONTEXT =
            PayloadType.of("PRESENCE_INTERACTION_CONTEXT", PresenceInteractionContextPayload.class);
    public static final PayloadType PARTNER =
            PayloadType.of("PRESENCE_PARTNER", PresencePartnerPayload.class);

    private PresencePayloadTypes() {
    }
}
