package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.settings.module.ia.IaSettingsAccess;
import com.rheinmetal.tianshu.client.settings.module.ia.IaSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.ir.IrSettingsAccess;
import com.rheinmetal.tianshu.client.settings.module.ir.IrSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.protocol.SettingsEventPublisher;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.settings.session.SettingsSessionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModuleSettingsOwnershipTest {
    @Test
    void irAndIaOwnIndependentSettingsCategoriesAndSessions() {
        TestSettingsAccess config = new TestSettingsAccess();

        assertModuleSettings(
                "module.ir",
                15,
                (registry, context) -> new IrSettingsRegistrySource(config).contribute(registry, context)
        );
        assertModuleSettings(
                "module.ia",
                40,
                (registry, context) -> new IaSettingsRegistrySource(config).contribute(registry, context)
        );
    }

    private static void assertModuleSettings(String moduleId, int order, SettingsContributor contributor) {
        SettingsSessionRegistry sessions = new SettingsSessionRegistry();
        SettingsCoordinator coordinator = new SettingsCoordinator(sessions, SettingsEventPublisher.NOOP);
        ModuleSettingsContext context = new ModuleSettingsContext() {
            @Override
            public SettingsCoordinator settingsCoordinator() {
                return coordinator;
            }

            @Override
            public void showStatus(UiText message, long durationMillis) {
            }
        };
        TianshuSettingsRegistry registry = new TianshuSettingsRegistry();

        contributor.contribute(registry, context);

        assertEquals(1, registry.categories().size());
        assertEquals(moduleId, registry.categories().getFirst().moduleId());
        assertEquals(order, registry.categories().getFirst().order());
        assertTrue(sessions.hasSession(moduleId));
    }

    @FunctionalInterface
    private interface SettingsContributor {
        void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context);
    }

    private static final class TestSettingsAccess implements IrSettingsAccess, IaSettingsAccess {
        private boolean irEnabled;
        private boolean iaEnabled;

        @Override
        public boolean isIrDiagnosticsEnabled() {
            return irEnabled;
        }

        @Override
        public void setIrDiagnosticsEnabled(boolean enabled) {
            irEnabled = enabled;
        }

        @Override
        public boolean isIaDiagnosticsEnabled() {
            return iaEnabled;
        }

        @Override
        public void setIaDiagnosticsEnabled(boolean enabled) {
            iaEnabled = enabled;
        }

        @Override
        public void save() {
        }
    }
}
