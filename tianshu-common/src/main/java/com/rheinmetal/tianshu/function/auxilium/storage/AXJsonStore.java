package com.rheinmetal.tianshu.function.auxilium.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AXJsonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final IGameEnvironment env;

    public AXJsonStore(IGameEnvironment env) {
        this.env = env;
    }

    public Optional<JsonObject> readObject(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                return Optional.empty();
            }
            return Optional.of(element.getAsJsonObject());
        } catch (Exception e) {
            warn("Failed to read json: " + path, e);
            return Optional.empty();
        }
    }

    public List<JsonObject> readJsonLines(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return List.of();
        }
        List<JsonObject> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonElement element = JsonParser.parseString(line);
                if (element != null && element.isJsonObject()) {
                    result.add(element.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            warn("Failed to read jsonl: " + path, e);
        }
        return List.copyOf(result);
    }

    public void writeObject(Path path, JsonObject object) {
        if (path == null || object == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(object, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            warn("Failed to write json: " + path, e);
        }
    }

    public void writeJsonLines(Path path, List<JsonObject> objects) {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                if (objects != null) {
                    for (JsonObject object : objects) {
                        if (object == null) {
                            continue;
                        }
                        writer.write(GSON.toJson(object));
                        writer.newLine();
                    }
                }
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            warn("Failed to write jsonl: " + path, e);
        }
    }

    private void warn(String message, Throwable throwable) {
        if (env != null) {
            env.warn(message + ": " + throwable.getMessage());
        }
    }
}
