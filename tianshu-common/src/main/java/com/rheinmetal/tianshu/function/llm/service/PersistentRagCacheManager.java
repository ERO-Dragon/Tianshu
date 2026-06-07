package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PersistentRagCacheManager implements RagCacheManager {

    private static final int DEFAULT_TOP_K = 4;
    private static final float DEFAULT_THRESHOLD = 0.7f;
    private static final int CACHE_MAGIC = 0x54535247; // TSRG
    private static final int CACHE_VERSION = 2;
    private static final int MAX_TEXT_BYTES = 1_048_576;
    private static final int MAX_VECTOR_DIMENSION = 65_536;

    private final IGameEnvironment env;
    private final EmbeddingService embeddingService;
    private final Path cacheDirectory;
    private final String namespace;
    private final ConcurrentMap<String, VectorStore> stores = new ConcurrentHashMap<>();

    public PersistentRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService, Path cacheDirectory) {
        this(env, embeddingService, cacheDirectory, "default");
    }

    public PersistentRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService, Path cacheDirectory, String namespace) {
        this.env = Objects.requireNonNull(env, "env");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService");
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.namespace = namespace == null || namespace.isBlank() ? "default" : namespace.trim();
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(cacheDirectory);
            loadAllStores();
            env.info("[RAG] Persistent cache initialized at: " + cacheDirectory);
        } catch (Exception e) {
            env.error("[RAG] Failed to initialize persistent cache", e);
        }
    }

    private void loadAllStores() {
        for (String uid : readManifest()) {
            loadStore(uid);
        }
        if (!stores.isEmpty()) {
            env.info("[RAG] Loaded " + stores.size() + " cached stores");
        }
    }

    private void loadStore(String uid) {
        if (uid == null || uid.isBlank()) {
            return;
        }
        Path vectorsFile = vectorsFile(uid);
        if (!Files.isRegularFile(vectorsFile)) {
            return;
        }

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(vectorsFile)))) {
            int magic = dis.readInt();
            int version = dis.readInt();
            if (magic != CACHE_MAGIC || version != CACHE_VERSION) {
                env.warn("[RAG] Ignored incompatible cache file for uid: " + uid);
                discardStoreFile(uid);
                return;
            }

            String fileNamespace = dis.readUTF();
            int dimension = dis.readInt();
            int count = dis.readInt();
            if (!namespace.equals(fileNamespace) || dimension <= 0 || dimension > MAX_VECTOR_DIMENSION || count < 0) {
                env.warn("[RAG] Ignored invalid cache metadata for uid: " + uid);
                discardStoreFile(uid);
                return;
            }

            List<String> texts = new ArrayList<>(count);
            List<float[]> vectors = new ArrayList<>(count);
            for (int entry = 0; entry < count; entry++) {
                int textLen = dis.readInt();
                if (textLen <= 0 || textLen > MAX_TEXT_BYTES) {
                    throw new IOException("Invalid cached text size: " + textLen);
                }
                byte[] textBytes = dis.readNBytes(textLen);
                if (textBytes.length != textLen) {
                    throw new IOException("Truncated cached text");
                }
                float[] vector = new float[dimension];
                for (int i = 0; i < dimension; i++) {
                    float value = dis.readFloat();
                    if (Float.isNaN(value) || Float.isInfinite(value)) {
                        throw new IOException("Invalid vector value");
                    }
                    vector[i] = value;
                }
                texts.add(new String(textBytes, StandardCharsets.UTF_8));
                vectors.add(vector);
            }

            if (!texts.isEmpty()) {
                VectorStore store = new VectorStore(uid);
                store.addAll(texts, vectors);
                stores.put(uid, store);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to load store for uid: " + uid, e);
            discardStoreFile(uid);
        }
    }

    private void saveStore(String uid) {
        VectorStore store = stores.get(uid);
        if (store == null || store.isEmpty()) {
            deleteStoreFile(uid);
            updateManifest(uid, false);
            return;
        }

        List<String> texts = store.getTexts();
        List<float[]> vectors = store.getVectors();
        if (texts.isEmpty() || texts.size() != vectors.size()) {
            return;
        }

        int dimension = vectors.get(0) == null ? 0 : vectors.get(0).length;
        if (dimension <= 0) {
            return;
        }

        try {
            Files.createDirectories(cacheDirectory);
            Path tmp = cacheDirectory.resolve(sanitizeFileName(uid) + ".tmp");
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                dos.writeInt(CACHE_MAGIC);
                dos.writeInt(CACHE_VERSION);
                dos.writeUTF(namespace);
                dos.writeInt(dimension);
                dos.writeInt(texts.size());

                for (int i = 0; i < texts.size(); i++) {
                    float[] vector = vectors.get(i);
                    if (!isUsableVector(vector) || vector.length != dimension) {
                        throw new IOException("Vector dimension mismatch while saving uid: " + uid);
                    }
                    byte[] textBytes = texts.get(i).getBytes(StandardCharsets.UTF_8);
                    dos.writeInt(textBytes.length);
                    dos.write(textBytes);
                    for (float val : vector) {
                        dos.writeFloat(val);
                    }
                }
            }
            moveIntoPlace(tmp, vectorsFile(uid));
            updateManifest(uid, true);
        } catch (Exception e) {
            env.error("[RAG] Failed to save store for uid: " + uid, e);
        }
    }

    private Set<String> readManifest() {
        Path manifestFile = manifestFile();
        Set<String> uids = new LinkedHashSet<>();
        if (!Files.isRegularFile(manifestFile)) {
            return uids;
        }
        try {
            for (String line : Files.readAllLines(manifestFile, StandardCharsets.UTF_8)) {
                if (line != null && !line.isBlank()) {
                    uids.add(line.trim());
                }
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to read manifest", e);
        }
        return uids;
    }

    private void updateManifest(String uid, boolean add) {
        if (uid == null || uid.isBlank()) {
            return;
        }
        try {
            Set<String> uids = readManifest();
            boolean changed = add ? uids.add(uid) : uids.remove(uid);
            if (!changed) {
                return;
            }
            Files.createDirectories(cacheDirectory);
            Files.write(manifestFile(), uids, StandardCharsets.UTF_8);
        } catch (Exception e) {
            env.error("[RAG] Failed to update manifest", e);
        }
    }

    private Path manifestFile() {
        return cacheDirectory.resolve("manifest.txt");
    }

    private Path vectorsFile(String uid) {
        return cacheDirectory.resolve(sanitizeFileName(uid) + ".bin");
    }

    private void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteStoreFile(String uid) {
        try {
            Files.deleteIfExists(vectorsFile(uid));
        } catch (Exception e) {
            env.error("[RAG] Failed to delete cache file for uid: " + uid, e);
        }
    }

    private void discardStoreFile(String uid) {
        stores.remove(uid);
        deleteStoreFile(uid);
        updateManifest(uid, false);
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "empty";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @Override
    public void index(String uid, List<String> texts) {
        List<String> validTexts = validTexts(texts);
        if (uid == null || uid.isBlank() || validTexts.isEmpty()) {
            return;
        }

        try {
            VectorStore store = stores.computeIfAbsent(uid, k -> new VectorStore(uid));
            List<String> textsToIndex = validTexts.stream()
                    .filter(text -> !store.containsText(text))
                    .toList();
            if (textsToIndex.isEmpty()) {
                return;
            }

            float[][] vectors = embeddingService.embed(textsToIndex);
            if (vectors == null || vectors.length != textsToIndex.size()) {
                env.warn("[RAG] Embedding result size mismatch for uid: " + uid);
                return;
            }

            List<String> filteredTexts = new ArrayList<>();
            List<float[]> filteredVectors = new ArrayList<>();

            for (int i = 0; i < textsToIndex.size(); i++) {
                String text = textsToIndex.get(i);
                float[] vector = vectors[i];
                if (isUsableVector(vector)) {
                    filteredTexts.add(text);
                    filteredVectors.add(VectorMath.normalize(vector));
                }
            }

            if (!filteredTexts.isEmpty()) {
                if (store.addAll(filteredTexts, filteredVectors)) {
                    saveStore(uid);
                    env.info("[RAG] Indexed and persisted " + filteredTexts.size() + " vectors for uid: " + uid);
                }
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to index texts for uid: " + uid, e);
        }
    }

    @Override
    public List<RagSearchResult> search(String uid, String queryText, int topK, float threshold) {
        if (uid == null || uid.isBlank() || queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            float[] queryVector = embeddingService.embed(queryText);
            if (!isUsableVector(queryVector)) {
                return List.of();
            }

            if (!stores.containsKey(uid)) {
                loadStore(uid);
            }

            VectorStore store = stores.get(uid);
            if (store == null || store.isEmpty()) {
                return List.of();
            }

            int effectiveTopK = topK > 0 ? topK : DEFAULT_TOP_K;
            float effectiveThreshold = threshold > 0f && threshold <= 1f ? threshold : DEFAULT_THRESHOLD;
            return store.search(VectorMath.normalize(queryVector), effectiveTopK, effectiveThreshold);
        } catch (Exception e) {
            env.error("[RAG] Failed to search for uid: " + uid, e);
            return List.of();
        }
    }

    @Override
    public void evict(String uid) {
        if (uid == null || uid.isBlank()) {
            return;
        }
        stores.remove(uid);
        deleteStoreFile(uid);
        updateManifest(uid, false);
        env.info("[RAG] Evicted all vectors for uid: " + uid);
    }

    @Override
    public void evict(String uid, String content) {
        if (uid == null || uid.isBlank() || content == null) {
            return;
        }
        VectorStore store = stores.get(uid);
        if (store == null) {
            loadStore(uid);
            store = stores.get(uid);
        }
        if (store != null) {
            if (store.remove(content)) {
                saveStore(uid);
                env.info("[RAG] Evicted content from uid: " + uid);
            }
        }
    }

    @Override
    public boolean hasCache(String uid) {
        if (uid == null || uid.isBlank()) {
            return false;
        }
        VectorStore store = stores.get(uid);
        if (store != null && !store.isEmpty()) {
            return true;
        }
        if (Files.isRegularFile(vectorsFile(uid))) {
            loadStore(uid);
            VectorStore loaded = stores.get(uid);
            return loaded != null && !loaded.isEmpty();
        }
        return false;
    }

    @Override
    public CacheStats getStats() {
        int uidCount = stores.size();
        int totalChunks = stores.values().stream().mapToInt(VectorStore::size).sum();
        long cacheSize = 0L;
        try (var walk = Files.exists(cacheDirectory) ? Files.walk(cacheDirectory) : java.util.stream.Stream.<Path>empty()) {
            cacheSize = walk.filter(Files::isRegularFile).mapToLong(this::safeSize).sum();
        } catch (Exception ignored) {
        }
        return new CacheStats(uidCount, totalChunks, cacheSize);
    }

    @Override
    public void clear() {
        stores.clear();
        try {
            if (Files.exists(cacheDirectory)) {
                try (var walk = Files.walk(cacheDirectory)) {
                    walk.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            env.info("[RAG] Cleared all caches and deleted files");
        } catch (Exception e) {
            env.error("[RAG] Failed to clear cache files", e);
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static List<String> validTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static boolean isUsableVector(float[] vector) {
        if (vector == null || vector.length == 0 || vector.length > MAX_VECTOR_DIMENSION) {
            return false;
        }
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return false;
            }
        }
        return true;
    }
}
