package com.rheinmetal.tianshu.core.config;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.config.ModuleConfigStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * A module's settings, stored as one {@code .properties} file per module id.
 *
 * <p>Flat key/value is the whole point: the kernel can persist any module's settings without knowing
 * what they mean, and a module adds a key by reading one. {@link Properties} handles escaping, so a
 * list survives a round trip without a separator the caller has to avoid.</p>
 */
public final class PropertyModuleConfigStore implements ModuleConfigStore {

    private static final String LIST_SEPARATOR = "\n";

    private final Path file;
    private final LogSink logs;
    private final Properties values = new Properties();
    private boolean loaded;
    private boolean dirty;

    public PropertyModuleConfigStore(Path file, LogSink logs) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.logs = logs == null ? LogSink.NOOP : logs;
    }

    public Path file() {
        return file;
    }

    public synchronized String getString(String key, String fallback) {
        loadOnce();
        String value = values.getProperty(key);
        return value == null ? fallback : value;
    }

    public synchronized boolean getBoolean(String key, boolean fallback) {
        String value = getString(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    public synchronized int getInt(String key, int fallback) {
        return (int) getLong(key, fallback);
    }

    public synchronized long getLong(String key, long fallback) {
        String value = getString(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException malformed) {
            logs.warn("module config value is not a number, using the default: key=" + key + ", file=" + file);
            return fallback;
        }
    }

    public synchronized double getDouble(String key, double fallback) {
        String value = getString(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException malformed) {
            logs.warn("module config value is not a number, using the default: key=" + key + ", file=" + file);
            return fallback;
        }
    }

    public synchronized List<String> getStringList(String key, List<String> fallback) {
        String value = getString(key, null);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return List.copyOf(new ArrayList<>(Arrays.asList(value.split(LIST_SEPARATOR, -1))));
    }

    public synchronized void setString(String key, String value) {
        loadOnce();
        String next = value == null ? "" : value;
        if (next.equals(values.getProperty(key))) {
            return;
        }
        values.setProperty(key, next);
        dirty = true;
    }

    public void setBoolean(String key, boolean value) {
        setString(key, Boolean.toString(value));
    }

    public void setInt(String key, int value) {
        setString(key, Integer.toString(value));
    }

    public void setLong(String key, long value) {
        setString(key, Long.toString(value));
    }

    public void setDouble(String key, double value) {
        setString(key, Double.toString(value));
    }

    public void setStringList(String key, List<String> values) {
        setString(key, values == null ? "" : String.join(LIST_SEPARATOR, values));
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                values.store(out, "Tianshu module settings");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException failure) {
            logs.error("failed to save module config: " + file, failure);
        }
    }

    private void loadOnce() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IOException failure) {
            logs.error("failed to read module config: " + file, failure);
        }
    }
}
