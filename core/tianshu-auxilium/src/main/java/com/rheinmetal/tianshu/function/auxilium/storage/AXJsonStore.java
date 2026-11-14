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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AXJsonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson JSONL_GSON = new Gson();
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
            boolean warned = false;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonElement element = JsonParser.parseString(line);
                    if (element != null && element.isJsonObject()) {
                        result.add(element.getAsJsonObject());
                    }
                } catch (Exception e) {
                    if (!warned) {
                        warn("Skipped malformed jsonl record: " + path, e);
                        warned = true;
                    }
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
            replace(temp, path);
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
                        writer.write(JSONL_GSON.toJson(object));
                        writer.newLine();
                    }
                }
            }
            replace(temp, path);
        } catch (IOException e) {
            warn("Failed to write jsonl: " + path, e);
        }
    }

    public void appendJsonLine(Path path, JsonObject object) {
        if (path == null || object == null) {
            return;
        }
        appendJsonLines(path, List.of(object));
    }

    public void appendJsonLines(Path path, List<JsonObject> objects) {
        tryAppendJsonLines(path, objects);
    }

    public boolean tryAppendJsonLine(Path path, JsonObject object) {
        return object != null && tryAppendJsonLines(path, List.of(object));
    }

    public boolean tryAppendJsonLines(Path path, List<JsonObject> objects) {
        if (path == null || objects == null || objects.isEmpty()) {
            return false;
        }
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                for (JsonObject object : objects) {
                    if (object == null) {
                        continue;
                    }
                    writer.write(JSONL_GSON.toJson(object));
                    writer.newLine();
                }
            }
            return true;
        } catch (IOException e) {
            warn("Failed to append jsonl: " + path, e);
            return false;
        }
    }

    private void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void warn(String message, Throwable throwable) {
        if (env != null) {
            env.warn(message + ": " + throwable.getMessage());
        }
    }
}
