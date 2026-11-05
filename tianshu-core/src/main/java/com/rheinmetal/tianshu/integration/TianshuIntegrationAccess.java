package com.rheinmetal.tianshu.integration;

import com.rheinmetal.tianshu.api.assembly.SignalFailures;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Optional process-wide lookup for external integrations that cannot receive the host registration event.
 * Internal common modules must use injected ports instead of this fallback.
 *
 * <p>NeoForge has an event for "the host is assembled"; Fabric has no event bus to hang that on. The
 * loader-neutral answer is the listener half of this class: a mod registers while its jar loads, and the
 * listener runs when the API is published — or immediately, when it already is. That is what lets a Fabric
 * mod react at assembly time instead of polling {@link #isAvailable()} from a tick handler.</p>
 *
 * <p>Each listener runs at most once per published API instance. Registering after publication is therefore
 * safe, and a host that republishes for a new session notifies every listener again.</p>
 */
public final class TianshuIntegrationAccess {
    private static final AtomicReference<TianshuIntegrationApi> API = new AtomicReference<>();
    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<Consumer<TianshuIntegrationApi>> ASSEMBLED = new CopyOnWriteArrayList<>();
    private static final Map<Consumer<TianshuIntegrationApi>, TianshuIntegrationApi> DELIVERED =
            new IdentityHashMap<>();

    private TianshuIntegrationAccess() {
    }

    public static boolean isAvailable() {
        return API.get() != null;
    }

    public static TianshuIntegrationApi require() {
        TianshuIntegrationApi current = API.get();
        if (current == null) {
            throw new IllegalStateException("Tianshu integration API is not available");
        }
        return current;
    }

    public static TianshuIntegrationApi currentOrNull() {
        return API.get();
    }

    /**
     * Registers a callback for "the host has an integration API".
     *
     * <p>Idempotent: registering the same listener twice keeps one registration. A listener registered after
     * publication is invoked right away with the current instance.</p>
     */
    public static void registerAssembledListener(Consumer<TianshuIntegrationApi> listener) {
        Objects.requireNonNull(listener, "listener");
        TianshuIntegrationApi published;
        synchronized (LOCK) {
            ASSEMBLED.addIfAbsent(listener);
            published = API.get();
        }
        if (published != null) {
            deliver(listener, published);
        }
    }

    public static void unregisterAssembledListener(Consumer<TianshuIntegrationApi> listener) {
        if (listener == null) {
            return;
        }
        synchronized (LOCK) {
            ASSEMBLED.remove(listener);
            DELIVERED.remove(listener);
        }
    }

    /** Publishes the current host-owned API, replacing an older lifecycle instance if present. */
    public static void publish(TianshuIntegrationApi integrationApi) {
        TianshuIntegrationApi next = Objects.requireNonNull(integrationApi, "integrationApi");
        List<Consumer<TianshuIntegrationApi>> listeners;
        synchronized (LOCK) {
            API.set(next);
            listeners = List.copyOf(ASSEMBLED);
        }
        for (Consumer<TianshuIntegrationApi> listener : listeners) {
            deliver(listener, next);
        }
    }

    /** Clears the API only when the caller still owns the currently published instance. */
    public static void clear(TianshuIntegrationApi integrationApi) {
        if (API.compareAndSet(integrationApi, null)) {
            synchronized (LOCK) {
                DELIVERED.values().removeIf(delivered -> delivered == integrationApi);
            }
        }
    }

    /** Test seam: forgets every listener and delivery mark. */
    static void resetListenersForTests() {
        synchronized (LOCK) {
            ASSEMBLED.clear();
            DELIVERED.clear();
        }
    }

    /**
     * Runs one listener, once per API instance, with the failure contained.
     *
     * <p>A third-party callback that throws must not stop the other listeners or the host's own bootstrap: the
     * failure goes to the process-wide {@link SignalFailures} boundary and assembly continues.</p>
     */
    private static void deliver(Consumer<TianshuIntegrationApi> listener, TianshuIntegrationApi integrationApi) {
        synchronized (LOCK) {
            if (DELIVERED.get(listener) == integrationApi) {
                return;
            }
            DELIVERED.put(listener, integrationApi);
        }
        try {
            listener.accept(integrationApi);
        } catch (RuntimeException failure) {
            SignalFailures.report("integration", "assembled", failure);
        }
    }
}