package com.rheinmetal.tianshu.client.settings.module;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class ModuleDiagnosticsSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final String moduleId;
    private final String translationPrefix;
    private final int order;
    private final Supplier<Boolean> getter;
    private final Consumer<Boolean> setter;
    private final Runnable configSave;

    protected ModuleDiagnosticsSettingsRegistrySource(
            String moduleId,
            String translationPrefix,
            int order,
            Supplier<Boolean> getter,
            Consumer<Boolean> setter,
            Runnable configSave
    ) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.translationPrefix = Objects.requireNonNull(translationPrefix, "translationPrefix");
        this.order = order;
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = Objects.requireNonNull(setter, "setter");
        this.configSave = Objects.requireNonNull(configSave, "configSave");
    }

    @Override
    public final void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null) {
            return;
        }
        DiagnosticsSession session = new DiagnosticsSession(moduleId, translationPrefix, getter, setter, configSave);
        context.settingsSessions().registerOrReplace(session);
        registry.registerCategory(ModuleSettingsCategory.builder(moduleId)
                .title(module(translationPrefix, "title"))
                .description(module(translationPrefix, "description"))
                .order(order)
                .panel((panel, ignored) -> buildPanel(panel, session))
                .build());
    }

    private static void buildPanel(ModuleSettingsPanel panel, DiagnosticsSession session) {
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

        private DiagnosticsSession(
                String moduleId,
                String translationPrefix,
                Supplier<Boolean> getter,
                Consumer<Boolean> setter,
                Runnable configSave
        ) {
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
