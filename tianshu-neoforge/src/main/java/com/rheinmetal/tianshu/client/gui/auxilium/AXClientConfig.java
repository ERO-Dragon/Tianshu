package com.rheinmetal.tianshu.client.gui.auxilium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.AXAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AXClientConfig implements AXAssistantSettings, AXOutputSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "settings.json";
    private static final String LEGACY_FILE_NAME = "output.json";
    private static final AXOutputMode DEFAULT_MODE = AXOutputMode.UI_ONLY;
    private static final String DEFAULT_VOICE_STYLE = "ax";
    private static final String DEFAULT_WAKE_WORD = "天枢";

    private final Path path;
    private final Path legacyPath;
    private AXOutputMode outputMode = DEFAULT_MODE;
    private String ttsVoiceStyle = DEFAULT_VOICE_STYLE;
    private String wakeWord = DEFAULT_WAKE_WORD;

    public AXClientConfig(Path axConfigDir) {
        this.path = axConfigDir == null ? null : axConfigDir.resolve(FILE_NAME);
        this.legacyPath = axConfigDir == null ? null : axConfigDir.resolve(LEGACY_FILE_NAME);
        load();
    }

    @Override
    public synchronized AXOutputMode outputMode() {
        return outputMode;
    }

    public synchronized void setOutputMode(AXOutputMode outputMode) {
        this.outputMode = outputMode == null ? DEFAULT_MODE : outputMode;
    }

    @Override
    public synchronized String ttsVoiceStyle() {
        return ttsVoiceStyle == null || ttsVoiceStyle.isBlank() ? DEFAULT_VOICE_STYLE : ttsVoiceStyle;
    }

    public synchronized void setTtsVoiceStyle(String ttsVoiceStyle) {
        this.ttsVoiceStyle = ttsVoiceStyle == null || ttsVoiceStyle.isBlank() ? DEFAULT_VOICE_STYLE : ttsVoiceStyle.trim();
    }

    @Override
    public synchronized String wakeWord() {
        return wakeWord == null || wakeWord.isBlank() ? DEFAULT_WAKE_WORD : wakeWord;
    }

    public synchronized void setWakeWord(String wakeWord) {
        this.wakeWord = wakeWord == null || wakeWord.isBlank() ? DEFAULT_WAKE_WORD : wakeWord.trim();
    }

    public synchronized Path path() {
        return path;
    }

    public synchronized void load() {
        Path source = readablePath();
        if (source == null) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            if (object.has("outputMode")) {
                outputMode = AXOutputMode.fromName(object.get("outputMode").getAsString(), DEFAULT_MODE);
            }
            if (object.has("ttsVoiceStyle")) {
                setTtsVoiceStyle(object.get("ttsVoiceStyle").getAsString());
            }
            if (object.has("wakeWord")) {
                setWakeWord(object.get("wakeWord").getAsString());
            }
        } catch (Exception ignored) {
            outputMode = DEFAULT_MODE;
            ttsVoiceStyle = DEFAULT_VOICE_STYLE;
            wakeWord = DEFAULT_WAKE_WORD;
        }
    }

    public synchronized void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("outputMode", outputMode().name());
            object.addProperty("ttsVoiceStyle", ttsVoiceStyle());
            object.addProperty("wakeWord", wakeWord());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(object, writer);
            }
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
        }
    }

    private Path readablePath() {
        if (path != null && Files.isRegularFile(path)) {
            return path;
        }
        if (legacyPath != null && Files.isRegularFile(legacyPath)) {
            return legacyPath;
        }
        return null;
    }
}
