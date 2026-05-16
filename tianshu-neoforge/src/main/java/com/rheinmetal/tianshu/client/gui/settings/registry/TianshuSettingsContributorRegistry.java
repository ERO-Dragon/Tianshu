package com.rheinmetal.tianshu.client.gui.settings.registry;

import com.rheinmetal.tianshu.client.gui.settings.api.TianshuSettingsContributor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TianshuSettingsContributorRegistry {
    private final CopyOnWriteArrayList<TianshuSettingsContributor> contributors = new CopyOnWriteArrayList<>();

    public void register(TianshuSettingsContributor contributor) {
        if (contributor != null) {
            contributors.addIfAbsent(contributor);
        }
    }

    public void unregister(TianshuSettingsContributor contributor) {
        if (contributor != null) {
            contributors.remove(contributor);
        }
    }

    public List<TianshuSettingsContributor> contributors() {
        return List.copyOf(contributors);
    }
}
