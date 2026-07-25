package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsAccess;
import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GlobalDebugSettingsSessionTest {
    @Test
    void toggleChangesOnlyDraftUntilSaved() {
        TestAccess access = new TestAccess();
        GlobalDebugSettingsSession session = new GlobalDebugSettingsSession(access);

        session.toggle();

        assertTrue(session.enabled());
        assertTrue(session.dirty());
        assertFalse(access.enabled);
        assertFalse(access.saved);

        session.save();

        assertTrue(access.enabled);
        assertTrue(access.saved);
        assertFalse(session.dirty());
    }

    @Test
    void resetRestoresPersistedValue() {
        TestAccess access = new TestAccess();
        access.enabled = true;
        GlobalDebugSettingsSession session = new GlobalDebugSettingsSession(access);

        session.toggle();
        session.reset();

        assertTrue(session.enabled());
        assertFalse(session.dirty());
    }

    private static final class TestAccess implements GlobalDebugSettingsAccess {
        private boolean enabled;
        private boolean saved;

        @Override
        public boolean isDebugEnabled() {
            return enabled;
        }

        @Override
        public void setDebugEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void save() {
            saved = true;
        }
    }
}
