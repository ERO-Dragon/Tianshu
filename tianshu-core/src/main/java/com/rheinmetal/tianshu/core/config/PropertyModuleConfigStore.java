package com.rheinmetal.tianshu.core.config;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.config.ModuleConfigStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
    /**
     * Marks the unambiguous list encoding.
     *
     * <p>Joining on a newline cannot express an element that contains one, and an empty list and a single empty string
     * both end up as "". The prefixed form states each element's length instead, so nothing is ambiguous; values
     * without the prefix are read the old way, which keeps existing files working.</p>
     */
    private static final String LIST_VERSION = "v2:";

    private final Path file;
    private final LogSink logs;
    private final Properties values = new Properties();
    private boolean loaded;
    private boolean dirty;
    /** True when the file on disk could not be parsed; the next save keeps it as {@code .corrupt} first. */
    private boolean corrupt;

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
        long value = getLong(key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            logs.warn("module config value is out of int range, using the default: key=" + key + ", file=" + file);
            return fallback;
        }
        return (int) value;
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
        loadOnce();
        // Read the raw property rather than going through getString: "present but empty" has to stay distinguishable
        // from "absent", or a list the module deliberately emptied would come back as the fallback on the next read.
        String value = values.getProperty(key);
        if (value == null) {
            return fallback;
        }
        if (value.isEmpty()) {
            return List.of();
        }
        if (value.startsWith(LIST_VERSION)) {
            return List.copyOf(decodeList(value.substring(LIST_VERSION.length()), key));
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
        setString(key, values == null ? "" : encodeList(values));
    }

    /** {@code <length>:<text>} per element: the length makes separators and empty strings unambiguous. */
    private static String encodeList(List<String> values) {
        if (values.isEmpty()) {
            return LIST_VERSION;
        }
        StringBuilder encoded = new StringBuilder(LIST_VERSION);
        for (String value : values) {
            String element = value == null ? "" : value;
            encoded.append(element.length()).append(':').append(element);
        }
        return encoded.toString();
    }

    private List<String> decodeList(String encoded, String key) {
        List<String> decoded = new ArrayList<>();
        int index = 0;
        while (index < encoded.length()) {
            int colon = encoded.indexOf(':', index);
            if (colon < 0) {
                logs.warn("module config list is truncated, treating the rest as one element: key=" + key
                        + ", file=" + file);
                decoded.add(encoded.substring(index));
                return decoded;
            }
            int length;
            try {
                length = Integer.parseInt(encoded.substring(index, colon));
            } catch (NumberFormatException malformed) {
                logs.warn("module config list has an unreadable length, treating the rest as one element: key=" + key
                        + ", file=" + file);
                decoded.add(encoded.substring(index));
                return decoded;
            }
            int start = colon + 1;
            int end = Math.min(encoded.length(), start + Math.max(0, length));
            decoded.add(encoded.substring(start, end));
            index = end;
        }
        return decoded;
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
            if (corrupt) {
                preserveUnreadableFile();
            }
            // A unique temporary name, not a fixed ".tmp": two stores for the same module (or a crash mid-write)
            // must never clobber each other's staging file.
            Path directory = parent == null ? file.toAbsolutePath().getParent() : parent;
            Path temporary = Files.createTempFile(directory, file.getFileName() + ".", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    values.store(out, "Tianshu module settings");
                }
                try {
                    // Atomic on the same filesystem; a partially written config would be worse than no config.
                    Files.move(temporary, file,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            dirty = false;
        } catch (IOException failure) {
            // A silent failure here becomes a lie in the UI: the caller cannot tell "written" from
            // "not written", commits its drafts and reports success. Surfacing it is what lets the
            // settings transaction roll back and the page say "failed".
            logs.error("failed to save module config: " + file, failure);
            throw new java.io.UncheckedIOException("failed to save module config: " + file, failure);
        }
    }

    /**
     * Moves a file this store could not read out of the way before the first save.
     *
     * <p>Deliberately defensive: when {@link Files#notExists} confirms the file is absent the overwrite loses
     * nothing, but in every other case — present, or existence unknown because the check itself failed — the
     * original is moved aside, and if even that fails the save fails. Overwriting without a backup is the one
     * outcome this method exists to make impossible.</p>
     */
    private void preserveUnreadableFile() throws IOException {
        if (Files.notExists(file)) {
            corrupt = false;
            return;
        }
        Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                StandardCopyOption.REPLACE_EXISTING);
        corrupt = false;
    }

    private void loadOnce() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (NoSuchFileException absent) {
            // First run: there is nothing on disk to merge with, so the defaults are the file. This is the only
            // failure that may be followed by an overwrite without a backup.
        } catch (IOException unreadable) {
            // The file exists but this process cannot read it — a locked file on Windows, an ACL change, a flaky
            // share. Treated exactly like a damaged file: save() backs it up first, so a transient read failure
            // can never replace the player's keys (api keys, prompts, paths) with defaults.
            logs.error("failed to read module config, will not overwrite it: " + file, unreadable);
            corrupt = true;
        } catch (IllegalArgumentException malformed) {
            // Properties reports a malformed \\uXXXX escape as an unchecked exception; letting it escape
            // would fail the module's prepare() instead of starting with defaults.
            logs.error("module config contains a malformed escape sequence, starting from defaults: " + file, malformed);
            corrupt = true;
        }
    }
}
