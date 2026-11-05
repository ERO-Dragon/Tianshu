package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.TianshuSettingsContributor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Settings pages declared by mods that are not Tianshu modules.
 *
 * <p>There are two lifetimes for such a declaration, and the loader decides which one a mod gets:</p>
 * <ul>
 *   <li><b>Per session</b> — NeoForge posts {@code TianshuIntegrationRegisterEvent} every time the host
 *       assembles, so a NeoForge mod registers into the session-scoped
 *       {@link TianshuSettingsContributorRegistry} the event hands it.</li>
 *   <li><b>Per process</b> — Fabric has no event to hang that on. A Fabric mod declares its page once, while
 *       its jar loads, and this list keeps it for the life of the process.</li>
 * </ul>
 *
 * <p>{@link ExternalSettingsRegistrySource} composes both; it is the only place that has to know there are two
 * lifetimes. Declarations are keyed by contribution identity and are idempotent.</p>
 */
public final class ExternalSettingsContributors {
    private static final CopyOnWriteArrayList<TianshuSettingsContributor> CONTRIBUTORS = new CopyOnWriteArrayList<>();

    private ExternalSettingsContributors() {
    }

    public static void register(TianshuSettingsContributor contributor) {
        if (contributor != null) {
            CONTRIBUTORS.addIfAbsent(contributor);
        }
    }

    public static void unregister(TianshuSettingsContributor contributor) {
        if (contributor != null) {
            CONTRIBUTORS.remove(contributor);
        }
    }

    public static List<TianshuSettingsContributor> contributors() {
        return List.copyOf(CONTRIBUTORS);
    }

    /** Test seam: a test process starts with no declarations. */
    static void resetForTests() {
        CONTRIBUTORS.clear();
    }
}