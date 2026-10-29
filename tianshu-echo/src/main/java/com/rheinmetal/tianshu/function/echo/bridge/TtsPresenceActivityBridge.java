package com.rheinmetal.tianshu.function.echo.bridge;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.presence.PresenceActivityPublisher;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

/**
 * The only place echo touches presence's activity contract.
 *
 * <p>Presence is an optional integration here: the HUD shows TTS activity when it is installed and echo is completely
 * unaffected when it is not. The bridge class itself carries no presence type in its signature; the nested
 * implementation is only loaded after the installed-mod check, so an absent presence never causes a class-load.</p>
 */
public abstract class TtsPresenceActivityBridge {

    /** Presence mod id used by the guard; loader identity, not a protocol module id. */
    public static final String PRESENCE_MOD_ID = "tianshu_presence";

    public enum Kind {
        LOADING,
        PROCESSING_TASK
    }

    public static TtsPresenceActivityBridge of(ModuleRuntimeAccess runtime, String sourceId) {
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

    private static final TtsPresenceActivityBridge NOOP = new TtsPresenceActivityBridge() {
        @Override
        public TianshuEnvelope publish(String activityId, Kind kind, boolean active, long ttlMillis) {
            return null;
        }
    };

    private static final class Real extends TtsPresenceActivityBridge {
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
            };
        }
    }
}
