package com.rheinmetal.tianshu.integration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional process-wide lookup for external integrations that cannot receive the host registration event.
 * Internal common modules must use injected ports instead of this fallback.
 */
public final class TianshuIntegrationAccess {
    private static final AtomicReference<TianshuIntegrationApi> API = new AtomicReference<>();

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

    /** Publishes the current host-owned API, replacing an older lifecycle instance if present. */
    public static void publish(TianshuIntegrationApi integrationApi) {
        API.set(Objects.requireNonNull(integrationApi, "integrationApi"));
    }

    /** Clears the API only when the caller still owns the currently published instance. */
    public static void clear(TianshuIntegrationApi integrationApi) {
        API.compareAndSet(integrationApi, null);
    }
}
