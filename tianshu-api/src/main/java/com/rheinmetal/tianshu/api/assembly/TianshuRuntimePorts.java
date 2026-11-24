package com.rheinmetal.tianshu.api.assembly;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ports a module fills before any world session exists.
 *
 * <p>Most kernel ports travel through the assembly context, which only exists per session. A few things
 * the platform needs earlier — the keyword table must be loaded when the game reloads resources, long
 * before the player enters a world — so a module registers an implementation here while its jar loads.
 * The platform looks it up by interface and never learns which module provided it.</p>
 */
public final class TianshuRuntimePorts {

    private static final Map<Class<?>, Object> PORTS = new ConcurrentHashMap<>();

    private TianshuRuntimePorts() {
    }

    public static <T> void register(Class<T> port, T implementation) {
        if (port == null || implementation == null) {
            return;
        }
        PORTS.put(port, implementation);
    }

    public static <T> Optional<T> find(Class<T> port) {
        Object implementation = port == null ? null : PORTS.get(port);
        return implementation == null ? Optional.empty() : Optional.of(port.cast(implementation));
    }

    /**
     * Removes the registration for one port, without disturbing a newer implementation registered since.
     *
     * <p>What installed a port is responsible for removing it: a server-scoped implementation — one that holds a
     * {@code MinecraftServer} — otherwise stays reachable for the rest of the process after the world is gone.</p>
     *
     * @param expected the implementation that should be removed; when a different one is registered now, nothing
     *                 happens and nobody else's registration is dropped by mistake
     */
    public static <T> void unregister(Class<T> port, T expected) {
        if (port == null) {
            return;
        }
        if (expected == null) {
            PORTS.remove(port);
            return;
        }
        PORTS.remove(port, expected);
    }

    /** Drops every registration; tests use this. */
    public static void clear() {
        PORTS.clear();
    }
}
