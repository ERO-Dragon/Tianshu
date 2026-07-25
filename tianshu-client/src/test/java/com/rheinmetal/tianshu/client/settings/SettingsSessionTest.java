package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSessionBuilder;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsSessionRegistry;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.client.api.text.UiText;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void saveAllValidatesEveryDirtySessionBeforeSavingAnySession() {
        AtomicInteger firstSaveCount = new AtomicInteger();
        ModuleSettingsSession first = new ModuleSettingsSession() {
            @Override
            public String moduleId() {
                return "first";
            }

            @Override
            public boolean dirty() {
                return true;
            }

            @Override
            public SettingsSaveResult save() {
                firstSaveCount.incrementAndGet();
                return SettingsSaveResult.success(UiText.key("saved"), true, false, false);
            }

            @Override
            public void reset() {
            }
        };
        ModuleSettingsSession invalid = new ModuleSettingsSession() {
            @Override
            public String moduleId() {
                return "invalid";
            }

            @Override
            public boolean dirty() {
                return true;
            }

            @Override
            public SettingsValidationResult validate() {
                return SettingsValidationResult.failure(UiText.key("invalid"));
            }

            @Override
            public SettingsSaveResult save() {
                throw new AssertionError("an invalid session must never be saved");
            }

            @Override
            public void reset() {
            }
        };
        SettingsSessionRegistry sessions = new SettingsSessionRegistry();
        sessions.register(first);
        sessions.register(invalid);

        SettingsSaveResult result = sessions.saveAll();

        assertFalse(result.success());
        assertEquals(SettingsSaveResult.FailureType.VALIDATION, result.failureType());
        assertEquals(0, firstSaveCount.get());
    }

    @Test
    void registeringSameModuleIdKeepsOneSession() {
        SettingsSessionRegistry sessions = new SettingsSessionRegistry();
        sessions.register(new ModuleSettingsSessionBuilder("module").build());
        sessions.register(new ModuleSettingsSessionBuilder("module").build());

        assertEquals(1, sessions.sessions().size());
    }
}
