package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.TianshuSettingsContributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The pages contributed by code outside the Tianshu module system.
 *
 * <p>Two sources, one list: the session-scoped registry the loader hands over (NeoForge's registration event
 * fills it again every assembly) and the process-scoped {@link ExternalSettingsContributors} (the only path a
 * Fabric mod has). A contributor present in both is applied once — identity, not equality, decides, because a
 * contributor is a callback object, not a value.</p>
 *
 * <p>The registry itself sorts pages by order and module id, so the order the two sources are merged in does not
 * reach the player.</p>
 */
public final class ExternalSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final TianshuSettingsContributorRegistry registry;

    public ExternalSettingsRegistrySource(TianshuSettingsContributorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void contribute(TianshuSettingsRegistry settingsRegistry, ModuleSettingsContext context) {
        for (TianshuSettingsContributor contributor : contributors()) {
            contributor.contributeSettings(settingsRegistry, context);
        }
    }

    private List<TianshuSettingsContributor> contributors() {
        Set<TianshuSettingsContributor> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        if (registry != null) {
            unique.addAll(registry.contributors());
        }
        unique.addAll(ExternalSettingsContributors.contributors());
        return new ArrayList<>(unique);
    }
}