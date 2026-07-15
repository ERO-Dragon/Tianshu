package com.rheinmetal.tianshu.client.settings.module.diagnostics;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class InternalModuleDiagnosticsSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final DiagnosticsSettingsAccess config;

    public InternalModuleDiagnosticsSettingsRegistrySource(DiagnosticsSettingsAccess config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null) {
            return;
        }
        register(registry, context, "module.ir", "ir", 15, config::isIrDiagnosticsEnabled, config::setIrDiagnosticsEnabled);
        register(registry, context, "module.ia", "ia", 40, config::isIaDiagnosticsEnabled, config::setIaDiagnosticsEnabled);
    }

    private void register(
            TianshuSettingsRegistry registry,
            ModuleSettingsContext context,
            String moduleId,
            String translationPrefix,
            int order,
            Supplier<Boolean> getter,
            Consumer<Boolean> setter
    ) {
        DiagnosticsSession session = new DiagnosticsSession(moduleId, getter, setter, translationPrefix, config::save);
        context.settingsSessions().registerOrReplace(session);
        registry.registerCategory(ModuleSettingsCategory.builder(moduleId)
                .title(module(translationPrefix, "title"))
                .description(module(translationPrefix, "description"))
                .order(order)
                .panel((panel, ignored) -> buildPanel(panel, session))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, DiagnosticsSession session) {
        panel.toggles(session.moduleId() + ".diagnostics", common("section.diagnostics"), toggles -> toggles
                .toggle(session.moduleId() + ".diagnostics.enabled", common("option.diagnostics_enabled"), session.enabled));
    }

    private static UiText module(String module, String key, Object... args) {
        return UiText.key("tianshu.gui." + module + "." + key, args);
    }

    private static UiText common(String key, Object... args) {
        return UiText.key("tianshu.gui.common." + key, args);
    }

    private static final class DiagnosticsSession implements ModuleSettingsSession {
        private final String moduleId;
        private final String translationPrefix;
        private final Runnable configSave;
        private final MutableSettingsValue<Boolean> enabled;

        private DiagnosticsSession(String moduleId, Supplier<Boolean> getter, Consumer<Boolean> setter, String translationPrefix, Runnable configSave) {
            this.moduleId = moduleId;
            this.translationPrefix = translationPrefix;
            this.configSave = configSave;
            this.enabled = new MutableSettingsValue<>(getter, setter);
        }

        @Override
        public String moduleId() {
            return moduleId;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty();
        }

        @Override
        public SettingsValidationResult validate() {
            return SettingsValidationResult.successful();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            enabled.save();
            configSave.run();
            return SettingsSaveResult.success(module(translationPrefix, "message.saved"), changed, false, false);
        }

        @Override
        public void reset() {
            enabled.reset();
        }
    }
}
