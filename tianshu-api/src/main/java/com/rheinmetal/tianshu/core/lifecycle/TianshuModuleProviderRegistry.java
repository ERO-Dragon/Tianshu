package com.rheinmetal.tianshu.core.lifecycle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where modules announce themselves.
 *
 * <p>A module's entrypoint registers a factory while its jar loads; the kernel reads the list once per
 * session. That inversion is what lets the kernel ship without knowing which modules exist: an absent
 * jar simply never adds a factory, and nothing in the kernel has to be edited to remove one.</p>
 */
public final class TianshuModuleProviderRegistry {

    private static final List<TianshuModuleProviderFactory> FACTORIES = new CopyOnWriteArrayList<>();

    private TianshuModuleProviderRegistry() {
    }

    public static void register(TianshuModuleProviderFactory factory) {
        if (factory == null) {
            return;
        }
        FACTORIES.add(factory);
    }

    public static List<TianshuModuleProviderFactory> factories() {
        return List.copyOf(FACTORIES);
    }

    /** Drops every registration; headless assembly and tests use this. */
    public static void clear() {
        FACTORIES.clear();
    }
}
