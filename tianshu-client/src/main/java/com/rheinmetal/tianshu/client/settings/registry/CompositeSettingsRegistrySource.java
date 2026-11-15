package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.List;
import java.util.Objects;

/**
 * Concatenates the settings pages of several sources.
 *
 * <p>A source that fails to contribute costs its own block, not the settings screen. The failure is reported twice:
 * a short in-game status so the player knows a section is missing, and a log line with the exception — the status
 * disappears after four seconds, and with no log line the only evidence that a whole page vanished was that same
 * vanishing.</p>
 */
public final class CompositeSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final List<TianshuSettingsRegistrySource> sources;
    private final LogSink logs;

    public CompositeSettingsRegistrySource(List<TianshuSettingsRegistrySource> sources) {
        this(sources, LogSink.NOOP);
    }

    public CompositeSettingsRegistrySource(List<TianshuSettingsRegistrySource> sources, LogSink logs) {
        this.sources = sources == null ? List.of() : sources.stream().filter(Objects::nonNull).toList();
        this.logs = logs == null ? LogSink.NOOP : logs;
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
                String reason = exception.getMessage();
                logs.error("settings.contribute_failed source=" + source.getClass().getName(), exception);
                if (context != null) {
                    context.showStatus(
                            UiText.key("tianshu.gui.settings.status.module_unavailable", reason == null ? "" : reason),
                            4000
                    );
                }
            }
        }
    }
}