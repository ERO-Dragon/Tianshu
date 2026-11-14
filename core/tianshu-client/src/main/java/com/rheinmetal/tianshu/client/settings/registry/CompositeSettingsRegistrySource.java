package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.List;

public final class CompositeSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final List<TianshuSettingsRegistrySource> sources;

    public CompositeSettingsRegistrySource(List<TianshuSettingsRegistrySource> sources) {
        this.sources = sources == null ? List.of() : sources.stream().filter(java.util.Objects::nonNull).toList();
    }

    public static CompositeSettingsRegistrySource of(TianshuSettingsRegistrySource... sources) {
        return new CompositeSettingsRegistrySource(sources == null ? List.of() : List.of(sources));
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        for (TianshuSettingsRegistrySource source : sources) {
            if (source == null) {
                continue;
            }
            try {
                source.contribute(registry, context);
            } catch (RuntimeException exception) {
                if (context != null) {
                    String reason = exception.getMessage();
                    context.showStatus(
                            UiText.key("tianshu.gui.settings.status.module_unavailable", reason == null ? "" : reason),
                            4000
                    );
                }
            }
        }
    }
}
