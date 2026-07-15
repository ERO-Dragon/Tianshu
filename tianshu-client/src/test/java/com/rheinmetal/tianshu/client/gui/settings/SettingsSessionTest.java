package com.rheinmetal.tianshu.client.gui.settings;

import com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSessionBuilder;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSessionRegistry;
import com.rheinmetal.tianshu.client.ui.UiText;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSessionTest {
    @Test
    void saveWritesDraftOnlyAfterValidation() {
        AtomicReference<String> stored = new AtomicReference<>("before");
        MutableSettingsValue<String> value = new MutableSettingsValue<>(stored::get, stored::set, text -> text != null && !text.isBlank());
        ModuleSettingsSession session = new ModuleSettingsSessionBuilder("module")
                .value(value)
                .successMessage(UiText.key("saved"))
                .build();
        SettingsSessionRegistry sessions = new SettingsSessionRegistry();
        sessions.register(session);
        SettingsCoordinator coordinator = new SettingsCoordinator(sessions, null);

        value.set("after");
        SettingsSaveResult result = coordinator.save("module");

        assertTrue(result.success());
        assertTrue(result.changed());
        assertEquals("after", stored.get());
        assertFalse(session.dirty());
        assertEquals(UiText.key("saved"), result.message());
    }

    @Test
    void validationFailureDoesNotWriteRealConfiguration() {
        AtomicReference<String> stored = new AtomicReference<>("before");
        MutableSettingsValue<String> value = new MutableSettingsValue<>(stored::get, stored::set, text -> text != null && !text.isBlank());
        SettingsSessionRegistry sessions = new SettingsSessionRegistry();
        sessions.register(new ModuleSettingsSessionBuilder("module").value(value).build());

        value.set("");
        SettingsSaveResult result = sessions.save("module");

        assertFalse(result.success());
        assertEquals(SettingsSaveResult.FailureType.VALIDATION, result.failureType());
        assertEquals("before", stored.get());
        assertTrue(value.dirty());
    }

    @Test
    void replacingSessionDropsTheOldDraft() {
        SettingsSessionRegistry sessions = new SettingsSessionRegistry();
        MutableSettingsValue<Boolean> firstValue = new MutableSettingsValue<>(() -> false, ignored -> { });
        firstValue.set(true);
        sessions.register(new ModuleSettingsSessionBuilder("module").value(firstValue).build());

        MutableSettingsValue<Boolean> replacementValue = new MutableSettingsValue<>(() -> false, ignored -> { });
        sessions.registerOrReplace(new ModuleSettingsSessionBuilder("module").value(replacementValue).build());

        assertEquals(1, sessions.sessions().size());
        assertFalse(sessions.dirty("module"));
    }
}
