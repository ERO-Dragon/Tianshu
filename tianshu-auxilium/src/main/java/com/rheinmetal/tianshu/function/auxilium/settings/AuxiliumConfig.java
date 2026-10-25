package com.rheinmetal.tianshu.function.auxilium.settings;

import com.rheinmetal.tianshu.api.config.ModuleConfigStore;
import com.rheinmetal.tianshu.function.auxilium.AuxiliumAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.storage.AuxiliumStorageConfiguration;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Auxilium's own settings.
 *
 * <p>Keys, defaults and storage location belong to this module; the kernel only supplies the store. The
 * output mode is derived from the reply-speech switch the same way it always was, so a player's old
 * choice keeps its meaning.</p>
 */
public final class AuxiliumConfig implements AuxiliumAssistantSettings, AuxiliumSettingsAccess,
        AuxiliumOutputSettings, AuxiliumStorageConfiguration {

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WAKE_WORD = "wakeWord";
    private static final String KEY_REPLY_SPEECH = "replySpeechEnabled";
    private static final String KEY_CHAT_THINKING = "chatThinkingEnabled";
    private static final String KEY_ALLOW_INTERRUPTION = "allowInterruption";

    private static final boolean DEFAULT_ENABLED = true;
    private static final String DEFAULT_WAKE_WORD = "";
    private static final boolean DEFAULT_REPLY_SPEECH = true;
    private static final boolean DEFAULT_CHAT_THINKING = false;
    private static final boolean DEFAULT_ALLOW_INTERRUPTION = true;

    private final ModuleConfigStore store;
    private final Path storageRoot;

    public AuxiliumConfig(ModuleConfigStore store, Path moduleDirectory) {
        this.store = Objects.requireNonNull(store, "store");
        this.storageRoot = Objects.requireNonNull(moduleDirectory, "moduleDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("ax")
                .resolve("cache");
    }

    @Override
    public boolean assistantEnabled() {
        return store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    @Override
    public void setAxEnabled(boolean enabled) {
        store.setBoolean(KEY_ENABLED, enabled);
    }

    @Override
    public String wakeWord() {
        String value = store.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD);
        return value == null ? DEFAULT_WAKE_WORD : value.trim();
    }

    @Override
    public void setAxWakeWord(String wakeWord) {
        store.setString(KEY_WAKE_WORD, wakeWord == null ? "" : wakeWord.trim());
    }

    @Override
    public boolean isAxReplySpeechEnabled() {
        return store.getBoolean(KEY_REPLY_SPEECH, DEFAULT_REPLY_SPEECH);
    }

    @Override
    public void setAxReplySpeechEnabled(boolean enabled) {
        store.setBoolean(KEY_REPLY_SPEECH, enabled);
    }

    @Override
    public boolean chatThinkingEnabled() {
        return store.getBoolean(KEY_CHAT_THINKING, DEFAULT_CHAT_THINKING);
    }

    @Override
    public void setAxChatThinkingEnabled(boolean enabled) {
        store.setBoolean(KEY_CHAT_THINKING, enabled);
    }

    @Override
    public boolean allowInterruption() {
        return store.getBoolean(KEY_ALLOW_INTERRUPTION, DEFAULT_ALLOW_INTERRUPTION);
    }

    @Override
    public void setAxAllowInterruption(boolean enabled) {
        store.setBoolean(KEY_ALLOW_INTERRUPTION, enabled);
    }

    @Override
    public AuxiliumOutputMode outputMode() {
        return isAxReplySpeechEnabled() ? AuxiliumOutputMode.TTS_ONLY : AuxiliumOutputMode.DISABLED;
    }

    @Override
    public Path storageRoot() {
        return storageRoot;
    }

    @Override
    public void save() {
        store.save();
    }
}
