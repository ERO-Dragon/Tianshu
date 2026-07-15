package com.rheinmetal.tianshu.client.gui.diagnostics;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.config.ClientConfig;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class InternalModuleDiagnosticsSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final ClientConfig config;

    public InternalModuleDiagnosticsSettingsRegistrySource(ClientConfig config) {
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

    private static Component module(String module, String key, Object... args) {
        return Component.translatable("tianshu.gui." + module + "." + key, args);
    }

    private static Component common(String key, Object... args) {
        return Component.translatable("tianshu.gui.common." + key, args);
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
