package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The settings pages modules contributed for the current session.
 *
 * <p>Modules register their page while assembling, which happens per world session; the settings screen
 * reads this list when it builds itself. Contributed pages go through the same composite as an external
 * integration's page, so there is one path, not two.</p>
 */
public final class ModuleSettingsSourceRegistry implements TianshuSettingsRegistrySource {

    private final List<TianshuSettingsRegistrySource> sources = new CopyOnWriteArrayList<>();
    private final LogSink logs;

    public ModuleSettingsSourceRegistry() {
        this(LogSink.NOOP);
    }

    /** @param logs where a module's broken contribution is reported; without it the page just disappears */
    public ModuleSettingsSourceRegistry(LogSink logs) {
        this.logs = logs == null ? LogSink.NOOP : logs;
    }

    public void add(TianshuSettingsRegistrySource source) {
        if (source != null) {
            sources.add(source);
        }
    }

    public void clear() {
        sources.clear();
    }

    public List<TianshuSettingsRegistrySource> sources() {
        return List.copyOf(sources);
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        List<TianshuSettingsRegistrySource> snapshot = sources();
        if (snapshot.isEmpty()) {
            return;
        }
        new CompositeSettingsRegistrySource(snapshot, logs).contribute(registry, context);
    }
}
