package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsAccess;
import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveTransaction;
import com.rheinmetal.tianshu.client.settings.session.SettingsSessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A save that throws must not lose the draft, and a retry must still land.
 *
 * <p>The transaction rolls the written values back when persisting fails, so the configuration keeps what the player
 * actually has on disk while the page keeps what they asked for. That separation is what makes "try again" honest.</p>
 */
class SettingsFailureRecoveryTest {

    @Test
    void moduleWriteFailurePreservesTheDraftAndAllowsRetry() {
        FailingAccess access = new FailingAccess();
        StubSession session = new StubSession(access);
        session.enable();
        SettingsSessionRegistry registry = new SettingsSessionRegistry();
        registry.register(session);

        SettingsSaveResult failure = assertDoesNotThrow(registry::saveAll);

        assertFalse(failure.success());
        assertEquals(SettingsSaveResult.FailureType.SAVE, failure.failureType());
        assertTrue(session.dirty(), "the player's change must survive a failed write");
        assertFalse(access.hudEnabled, "the stored value must not change when writing fails");

        access.fail = false;
        assertTrue(registry.saveAll().success());
        assertFalse(session.dirty());
        assertTrue(access.hudEnabled);
        assertEquals(2, access.attempts);
    }

    @Test
    void debugWriteFailurePreservesTheDraftAndAllowsRetry() {
        Access config = new Access();
        GlobalDebugSettingsSession session = new GlobalDebugSettingsSession(config);
        session.toggle();
        SettingsSessionRegistry registry = new SettingsSessionRegistry();
        registry.register(session);

        SettingsSaveResult failure = assertDoesNotThrow(registry::saveAll);

        assertFalse(failure.success());
        assertEquals(SettingsSaveResult.FailureType.SAVE, failure.failureType());
        assertTrue(session.dirty());
        assertFalse(config.debug);

        config.fail = false;
        assertTrue(registry.saveAll().success());
        assertFalse(session.dirty());
        assertEquals(2, config.attempts);
    }

    private static final class FailingAccess {
        boolean hudEnabled;
        boolean fail = true;
        int attempts;

        void save() {
            attempts++;
            if (fail) {
                throw new IllegalStateException("simulated write failure");
            }
        }
    }

    private static final class StubSession implements ModuleSettingsSession {
        private final FailingAccess access;
        private final MutableSettingsValue<Boolean> hudEnabled;

        private StubSession(FailingAccess access) {
            this.access = access;
            this.hudEnabled = new MutableSettingsValue<>(() -> access.hudEnabled, value -> access.hudEnabled = value);
        }

        private void enable() {
            hudEnabled.set(true);
        }

        @Override
        public String moduleId() {
            return "test.module";
        }

        @Override
        public boolean dirty() {
            return hudEnabled.dirty();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            new SettingsSaveTransaction(hudEnabled).commit(access::save);
            return SettingsSaveResult.success(UiText.literal("saved"), changed, false, false);
        }

        @Override
        public void reset() {
            hudEnabled.reset();
        }
    }

    private static final class Access implements GlobalDebugSettingsAccess {
        boolean debug;
        boolean fail = true;
        int attempts;

        @Override
        public boolean isDebugEnabled() {
            return debug;
        }

        @Override
        public void setDebugEnabled(boolean enabled) {
            this.debug = enabled;
        }

        @Override
        public void save() {
            attempts++;
            if (fail) {
                throw new IllegalStateException("simulated write failure");
            }
        }
    }
    @Test
    void aPartialSaveReportsWhatLandedAndKeepsItsReloadRequirement() {
        // Module A saves and asks for a reload; module B then fails. The screen still says "failed" for B, but A is
        // already on disk — and a retry only touches B, so A's reload requirement would be lost without this.
        FailingAccess first = new FailingAccess();
        first.fail = false;
        FailingAccess second = new FailingAccess();
        second.fail = true;
        SettingsSessionRegistry registry = new SettingsSessionRegistry();
        registry.register(new ReloadingSession("a.module", first));
        registry.register(new FailingSession("b.module", second));

        SettingsSaveResult result = registry.saveAll();

        assertFalse(result.success());
        assertEquals(SettingsSaveResult.FailureType.SAVE, result.failureType());
        assertEquals(List.of("a.module"), List.copyOf(result.savedModules()),
                "the caller has to know which modules already landed");
        assertTrue(result.requiresReload(), "the saved module's reload requirement must not vanish with the failure");
        assertTrue(first.hudEnabled, "the module that succeeded is on disk");
    }

    private static final class ReloadingSession implements ModuleSettingsSession {
        private final String moduleId;
        private final FailingAccess access;
        private final MutableSettingsValue<Boolean> value;

        private ReloadingSession(String moduleId, FailingAccess access) {
            this.moduleId = moduleId;
            this.access = access;
            this.value = new MutableSettingsValue<>(() -> access.hudEnabled, updated -> access.hudEnabled = updated);
            value.set(true);
        }

        @Override
        public String moduleId() {
            return moduleId;
        }

        @Override
        public boolean dirty() {
            return value.dirty();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            new SettingsSaveTransaction(value).commit(access::save);
            return SettingsSaveResult.success(UiText.literal("saved"), changed, false, true);
        }

        @Override
        public void reset() {
            value.reset();
        }
    }

    private static final class FailingSession implements ModuleSettingsSession {
        private final String moduleId;
        private final FailingAccess access;
        private final MutableSettingsValue<Boolean> value;

        private FailingSession(String moduleId, FailingAccess access) {
            this.moduleId = moduleId;
            this.access = access;
            this.value = new MutableSettingsValue<>(() -> access.hudEnabled, updated -> access.hudEnabled = updated);
            value.set(true);
        }

        @Override
        public String moduleId() {
            return moduleId;
        }

        @Override
        public boolean dirty() {
            return value.dirty();
        }

        @Override
        public SettingsSaveResult save() {
            new SettingsSaveTransaction(value).commit(access::save);
            return SettingsSaveResult.success(UiText.literal("saved"), true, false, false);
        }

        @Override
        public void reset() {
            value.reset();
        }
    }
}