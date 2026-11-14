package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

/**
 * The one way another module reports product activity to presence.
 *
 * <p>The activity contract is presence's, so the envelope construction lives here too: a producer keeps its own
 * envelope source (it is still the speaker), but never builds a presence topic, payload or type by hand. An optional
 * producer only constructs this class behind its installed-mod guard, so a build without presence never loads it.</p>
 */
public final class PresenceActivityPublisher extends AbstractProtocolAdapter {

    /** Activity kinds other modules may report; presence owns the mapping to its wire vocabulary. */
    public enum Kind {
        LOADING(PresenceActivityType.LOADING),
        PROCESSING_TASK(PresenceActivityType.PROCESSING_TASK),
        RECONNECTING(PresenceActivityType.RECONNECTING),
        ERROR(PresenceActivityType.ERROR),
        THINKING(PresenceActivityType.THINKING),
        RESPONDING(PresenceActivityType.RESPONDING),
        LISTENING(PresenceActivityType.LISTENING);

        private final PresenceActivityType type;

        Kind(PresenceActivityType type) {
            this.type = type;
        }
    }

    public PresenceActivityPublisher(ModuleRuntimeAccess runtime, String sourceId) {
        super(sourceId, sourceId, runtime, AdapterDefaults.standard());
    }

    public TianshuEnvelope publish(String activityId, Kind kind, boolean active, long ttlMillis) {
        PresenceActivityPayload payload = active
                ? PresenceActivityPayload.started(activityId, kind.type, Math.max(1L, ttlMillis))
                : PresenceActivityPayload.ended(activityId, kind.type);
        return publishTopic(PresenceTopics.ACTIVITY, PresencePayloadTypes.ACTIVITY, payload);
    }
}
