package com.rheinmetal.tianshu.function.weaving.bridge;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.presence.PresenceActivityPublisher;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.Locale;

/**
 * The only place weaving touches presence's activity contract.
 *
 * <p>Presence is an optional integration here: the cloud lane reports loading, request progress and its ambient
 * reconnecting/error notices when the HUD module is installed, and is completely unaffected when it is absent. The
 * nested implementation is only loaded after the installed-mod check, so an absent presence never causes a
 * class-load.</p>
 */
public abstract class LlmPresenceActivityBridge {

    /** Presence mod id used by the guard; loader identity, not a protocol module id. */
    public static final String PRESENCE_MOD_ID = "tianshu_presence";

    public enum Kind {
        LOADING,
        PROCESSING_TASK,
        RECONNECTING,
        ERROR
    }

    public static LlmPresenceActivityBridge of(ModuleRuntimeAccess runtime, String sourceId) {
        if (runtime == null) {
            return NOOP;
        }
        boolean installed = TianshuRuntimePorts.find(InstalledMods.class)
                .orElseGet(InstalledMods::none)
                .isLoaded(PRESENCE_MOD_ID);
        return installed ? new Real(runtime, sourceId) : NOOP;
    }

    /**
     * Opens or closes one activity.
     *
     * @return the envelope presence accepted, or {@code null} when presence is absent or the notice was not open
     */
    public abstract TianshuEnvelope publish(String activityId, Kind kind, boolean active, long ttlMillis);

    /** The activity id of an ambient runtime notice; the kind is part of the identity. */
    public static String runtimeActivityId(Kind kind) {
        if (kind != Kind.RECONNECTING && kind != Kind.ERROR) {
            throw new IllegalArgumentException("runtime activity must be RECONNECTING or ERROR");
        }
        return "llm.runtime." + kind.name().toLowerCase(Locale.ROOT);
    }

    private static final LlmPresenceActivityBridge NOOP = new LlmPresenceActivityBridge() {
        @Override
        public TianshuEnvelope publish(String activityId, Kind kind, boolean active, long ttlMillis) {
            return null;
        }
    };

    private static final class Real extends LlmPresenceActivityBridge {
        private final PresenceActivityPublisher publisher;

        private Real(ModuleRuntimeAccess runtime, String sourceId) {
            this.publisher = new PresenceActivityPublisher(runtime, sourceId);
        }

        @Override
        public TianshuEnvelope publish(String activityId, Kind kind, boolean active, long ttlMillis) {
            return publisher.publish(activityId, kindOf(kind), active, ttlMillis);
        }

        private static PresenceActivityPublisher.Kind kindOf(Kind kind) {
            return switch (kind) {
                case LOADING -> PresenceActivityPublisher.Kind.LOADING;
                case PROCESSING_TASK -> PresenceActivityPublisher.Kind.PROCESSING_TASK;
                case RECONNECTING -> PresenceActivityPublisher.Kind.RECONNECTING;
                case ERROR -> PresenceActivityPublisher.Kind.ERROR;
            };
        }
    }
}
