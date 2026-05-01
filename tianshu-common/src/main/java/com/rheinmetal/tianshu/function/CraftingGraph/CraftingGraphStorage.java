package com.rheinmetal.tianshu.function.CraftingGraph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class CraftingGraphStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CRASH_RECOVERY_FILE = "crash_recovery.json";
    private static final String FAVORITES_DIR = "favorites";
    private static final String HISTORY_DIR = "history";
    private static final long AUTO_SAVE_INTERVAL_MILLIS = 60_000L;

    private final Path rootDir;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Tianshu-CraftingGraph-Storage");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> pendingAutoSave;
    private volatile long lastAutoSaveMillis;
    private volatile String lastAutoSaveHash;

    public CraftingGraphStorage(Path rootDir) {
        this.rootDir = rootDir;
    }

    public void saveCrashRecovery(CraftingGraphSaveData data) throws IOException {
        write(rootDir.resolve(CRASH_RECOVERY_FILE), data);
    }

    public boolean scheduleCrashRecoverySave(CraftingGraphSaveData data, long nowMillis) {
        if (data == null) return false;
        if (nowMillis - lastAutoSaveMillis < AUTO_SAVE_INTERVAL_MILLIS) return false;
        if (pendingAutoSave != null && !pendingAutoSave.isDone()) return false;
        String json = GSON.toJson(data);
        String hash = sha256(json);
        if (hash.equals(lastAutoSaveHash)) {
            lastAutoSaveMillis = nowMillis;
            return false;
        }
        Path file = rootDir.resolve(CRASH_RECOVERY_FILE);
        pendingAutoSave = ioExecutor.submit(() -> {
            try {
                writeJson(file, json);
                lastAutoSaveHash = hash;
                lastAutoSaveMillis = nowMillis;
            } catch (IOException ignored) {
            }
        });
        return true;
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public CraftingGraphSaveData loadCrashRecovery() throws IOException {
        return read(rootDir.resolve(CRASH_RECOVERY_FILE));
    }

    public void deleteCrashRecovery() throws IOException {
        Files.deleteIfExists(rootDir.resolve(CRASH_RECOVERY_FILE));
    }

    public void saveFavorite(String name, CraftingGraphSaveData data) throws IOException {
        write(rootDir.resolve(FAVORITES_DIR).resolve(safeFileName(name) + ".json"), data);
    }

    public void saveHistory(String name, CraftingGraphSaveData data) throws IOException {
        write(rootDir.resolve(HISTORY_DIR).resolve(safeFileName(name) + ".json"), data);
        trimHistory(5);
    }

    public List<StoredGraphEntry> listFavorites() throws IOException {
        return listEntries(rootDir.resolve(FAVORITES_DIR));
    }

    public List<StoredGraphEntry> listHistory() throws IOException {
        return listEntries(rootDir.resolve(HISTORY_DIR));
    }

    public CraftingGraphSaveData loadEntry(StoredGraphEntry entry) throws IOException {
        return entry != null ? read(entry.getPath()) : null;
    }

    private List<StoredGraphEntry> listEntries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<StoredGraphEntry> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path path : stream.filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                CraftingGraphSaveData data = read(path);
                if (data == null || data.nodes == null || data.nodes.isEmpty()) continue;
                entries.add(new StoredGraphEntry(path, displayName(path, data), lastModified(path), data.nodes.size(), data.edges != null ? data.edges.size() : 0));
            }
        }
        entries.sort(Comparator.comparingLong(StoredGraphEntry::getModifiedAtMillis).reversed());
        return entries;
    }

    private String displayName(Path path, CraftingGraphSaveData data) {
        if (data != null && data.nodes != null && !data.nodes.isEmpty()) {
            CraftingGraphSaveData.NodeRecord first = data.nodes.get(0);
            if (first.displayName != null && !first.displayName.isBlank()) return first.displayName;
            if (first.itemId != null && !first.itemId.isBlank()) return first.itemId;
        }
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private void write(Path file, CraftingGraphSaveData data) throws IOException {
        writeJson(file, GSON.toJson(data));
    }

    private void writeJson(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private CraftingGraphSaveData read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        CraftingGraphSaveData data = GSON.fromJson(Files.readString(file), CraftingGraphSaveData.class);
        return data != null ? data : null;
    }

    private void trimHistory(int maxFiles) throws IOException {
        Path dir = rootDir.resolve(HISTORY_DIR);
        if (!Files.isDirectory(dir)) return;
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> Long.compare(lastModified(a), lastModified(b)))
                    .toList();
        }
        int extra = files.size() - maxFiles;
        for (int i = 0; i < extra; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private String safeFileName(String name) {
        String normalized = name == null || name.isBlank() ? "graph" : name.trim();
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static final class StoredGraphEntry {
        private final Path path;
        private final String displayName;
        private final long modifiedAtMillis;
        private final int nodeCount;
        private final int edgeCount;

        public StoredGraphEntry(Path path, String displayName, long modifiedAtMillis, int nodeCount, int edgeCount) {
            this.path = path;
            this.displayName = displayName;
            this.modifiedAtMillis = modifiedAtMillis;
            this.nodeCount = nodeCount;
            this.edgeCount = edgeCount;
        }

        public Path getPath() {
            return path;
        }

        public String getDisplayName() {
            return displayName;
        }

        public long getModifiedAtMillis() {
            return modifiedAtMillis;
        }

        public int getNodeCount() {
            return nodeCount;
        }

        public int getEdgeCount() {
            return edgeCount;
        }
    }
}
