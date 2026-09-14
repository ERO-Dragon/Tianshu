package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.settings.global.*;
import com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsAccess;
import com.rheinmetal.tianshu.client.settings.session.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsFailureRecoveryTest {
    @Test
    void presenceWriteFailurePreservesDraftRestoresConfigAndAllowsRetry() throws Exception {
        Access config = new Access();
        Class<?> type = Class.forName("com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsRegistrySource$PresenceSettingsSession");
        var constructor = type.getDeclaredConstructor(PresenceSettingsAccess.class);
        constructor.setAccessible(true);
        var session = (ModuleSettingsSession) constructor.newInstance(config);
        var field = type.getDeclaredField("hudEnabled");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var draft = (MutableSettingsValue<Boolean>) field.get(session);
        draft.set(true);
        verifyRecovery(config, session);
        assertTrue(config.hud);
    }

    @Test
    void debugWriteFailurePreservesDraftRestoresConfigAndAllowsRetry() {
        Access config = new Access();
        var session = new GlobalDebugSettingsSession(config);
        session.toggle();
        verifyRecovery(config, session);
        assertTrue(config.debug);
    }

    private void verifyRecovery(Access config, ModuleSettingsSession session) {
        var registry = new SettingsSessionRegistry();
        registry.register(session);
        var failure = assertDoesNotThrow(registry::saveAll);
        assertFalse(failure.success());
        assertEquals(SettingsSaveResult.FailureType.SAVE, failure.failureType());
        assertTrue(session.dirty());
        assertFalse(config.hud);
        assertFalse(config.debug);
        config.fail = false;
        assertTrue(registry.saveAll().success());
        assertFalse(session.dirty());
        assertEquals(2, config.attempts);
    }

    private static final class Access implements PresenceSettingsAccess, GlobalDebugSettingsAccess {
        boolean hud, text, debug, fail = true;
        int attempts;
        public boolean isPresenceHudEnabled() { return hud; }
        public void setPresenceHudEnabled(boolean value) { hud = value; }
        public boolean isPresenceStatusTextEnabled() { return text; }
        public void setPresenceStatusTextEnabled(boolean value) { text = value; }
        public boolean isDebugEnabled() { return debug; }
        public void setDebugEnabled(boolean value) { debug = value; }
        public void save() {
            attempts++;
            if (fail) throw new IllegalStateException("simulated write failure");
        }
    }
}
