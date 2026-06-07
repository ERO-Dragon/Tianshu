package com.rheinmetal.tianshu.client.gui.auxilium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputSettings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AXClientOutputConfig implements AXOutputSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "output.json";
    private static final AXOutputMode DEFAULT_MODE = AXOutputMode.UI_ONLY;
    private static final String DEFAULT_VOICE_STYLE = "ax";

    private final Path path;
    private AXOutputMode outputMode = DEFAULT_MODE;
    private String ttsVoiceStyle = DEFAULT_VOICE_STYLE;

    public AXClientOutputConfig(Path axConfigDir) {
        this.path = axConfigDir == null ? null : axConfigDir.resolve(FILE_NAME);
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

    public synchronized Path path() {
        return path;
    }

    public synchronized void load() {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            if (object.has("outputMode")) {
                outputMode = AXOutputMode.fromName(object.get("outputMode").getAsString(), DEFAULT_MODE);
            }
            if (object.has("ttsVoiceStyle")) {
                setTtsVoiceStyle(object.get("ttsVoiceStyle").getAsString());
            }
        } catch (Exception ignored) {
            outputMode = DEFAULT_MODE;
            ttsVoiceStyle = DEFAULT_VOICE_STYLE;
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
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(object, writer);
            }
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
        }
    }
}
