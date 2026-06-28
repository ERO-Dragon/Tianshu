package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;

public final class PresenceClientHooks {
    private static volatile PresenceClientRuntime runtime;

    private PresenceClientHooks() {
    }

    public static void bind(PresenceClientRuntime value) {
        runtime = value;
    }

    public static void clear(PresenceClientRuntime value) {
        if (runtime == value) {
            runtime = null;
        }
    }

    public static void recordAdvancementUpdate(Object nativePacket) {
        PresenceClientRuntime current = runtime;
        if (current != null) {
            current.recordAdvancementUpdate(nativePacket);
        }
    }

    public static PresenceContextSnapshot contextSnapshot() {
        PresenceClientRuntime current = runtime;
        return current == null ? PresenceContextSnapshot.empty() : current.contextSnapshot();
    }
}
