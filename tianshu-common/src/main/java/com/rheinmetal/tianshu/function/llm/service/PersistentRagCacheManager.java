package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PersistentRagCacheManager implements RagCacheManager {

    private static final int DEFAULT_TOP_K = 4;
    private static final float DEFAULT_THRESHOLD = 0.7f;

    private final IGameEnvironment env;
    private final EmbeddingService embeddingService;
    private final Path cacheDirectory;
    private final ConcurrentMap<String, VectorStore> stores = new ConcurrentHashMap<>();

    public PersistentRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService, Path cacheDirectory) {
        this.env = Objects.requireNonNull(env, "env");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService");
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
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
        try {
            Path manifestFile = cacheDirectory.resolve("manifest.txt");
            if (!Files.exists(manifestFile)) {
                return;
            }

            String manifestContent = Files.readString(manifestFile);
            for (String uid : manifestContent.split("\n")) {
                if (uid != null && !uid.isBlank()) {
                    loadStore(uid.trim());
                }
            }
            env.info("[RAG] Loaded " + stores.size() + " cached stores");
        } catch (Exception e) {
            env.error("[RAG] Failed to load manifest", e);
        }
    }

    private void loadStore(String uid) {
        try {
            Path vectorsFile = cacheDirectory.resolve(sanitizeFileName(uid) + ".bin");
            if (!Files.exists(vectorsFile)) {
                return;
            }

            List<String> texts = new ArrayList<>();
            List<float[]> vectors = new ArrayList<>();

            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(vectorsFile)))) {
                while (dis.available() > 0) {
                    int textLen = dis.readInt();
                    if (textLen <= 0 || textLen > 10000) break;
                    byte[] textBytes = new byte[textLen];
                    dis.readFully(textBytes);
                    texts.add(new String(textBytes, "UTF-8"));

                    int dim = embeddingService.getEmbeddingDimension();
                    float[] vector = new float[dim];
                    for (int i = 0; i < dim; i++) {
                        vector[i] = dis.readFloat();
                    }
                    vectors.add(vector);
                }
            }

            if (!texts.isEmpty()) {
                VectorStore store = new VectorStore(uid);
                store.addAll(texts, vectors);
                stores.put(uid, store);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to load store for uid: " + uid, e);
        }
    }

    private void saveStore(String uid) {
        VectorStore store = stores.get(uid);
        if (store == null || store.isEmpty()) {
            return;
        }

        try {
            Path vectorsFile = cacheDirectory.resolve(sanitizeFileName(uid) + ".bin");

            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(vectorsFile)))) {
                List<String> texts = store.getTexts();
                List<float[]> vectors = store.getVectors();

                for (int i = 0; i < texts.size(); i++) {
                    byte[] textBytes = texts.get(i).getBytes("UTF-8");
                    dos.writeInt(textBytes.length);
                    dos.write(textBytes);

                    float[] v = vectors.get(i);
                    for (float val : v) {
                        dos.writeFloat(val);
                    }
                }
            }

            updateManifest(uid, true);
            env.info("[RAG] Saved store for uid: " + uid);
        } catch (Exception e) {
            env.error("[RAG] Failed to save store for uid: " + uid, e);
        }
    }

    private void updateManifest(String uid, boolean add) {
        try {
            Path manifestFile = cacheDirectory.resolve("manifest.txt");
            Set<String> uids = new LinkedHashSet<>();

            if (Files.exists(manifestFile)) {
                String content = Files.readString(manifestFile);
                for (String line : content.split("\n")) {
                    if (line != null && !line.isBlank()) {
                        uids.add(line.trim());
                    }
                }
            }

            if (add) {
                uids.add(uid);
            } else {
                uids.remove(uid);
            }

            StringBuilder sb = new StringBuilder();
            for (String id : uids) {
                sb.append(id).append("\n");
            }
            Files.writeString(manifestFile, sb.toString());
        } catch (Exception e) {
            env.error("[RAG] Failed to update manifest", e);
        }
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @Override
    public void index(String uid, List<String> texts) {
        if (uid == null || texts == null || texts.isEmpty()) {
            return;
        }

        try {
            float[][] vectors = embeddingService.embed(texts);
            VectorStore store = stores.computeIfAbsent(uid, k -> new VectorStore(uid));

            List<String> filteredTexts = new ArrayList<>();
            List<float[]> filteredVectors = new ArrayList<>();

            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i);
                float[] vector = vectors[i];
                if (text != null && !text.isBlank() && vector != null && vector.length > 0) {
                    filteredTexts.add(text);
                    filteredVectors.add(VectorMath.normalize(vector));
                }
            }

            store.addAll(filteredTexts, filteredVectors);
            saveStore(uid);
            env.info("[RAG] Indexed and persisted " + filteredTexts.size() + " vectors for uid: " + uid);
        } catch (Exception e) {
            env.error("[RAG] Failed to index texts for uid: " + uid, e);
        }
    }

    @Override
    public List<RagSearchResult> search(String uid, String queryText, int topK, float threshold) {
        if (uid == null || queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            float[] queryVector = embeddingService.embed(queryText);

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
        VectorStore removed = stores.remove(uid);
        if (removed != null) {
            try {
                Path vectorsFile = cacheDirectory.resolve(sanitizeFileName(uid) + ".bin");
                Files.deleteIfExists(vectorsFile);
                updateManifest(uid, false);
                env.info("[RAG] Evicted all vectors for uid: " + uid);
            } catch (Exception e) {
                env.error("[RAG] Failed to evict store for uid: " + uid, e);
            }
        }
    }

    @Override
    public void evict(String uid, String content) {
        VectorStore store = stores.get(uid);
        if (store != null) {
            store.remove(content);
            saveStore(uid);
            env.info("[RAG] Evicted content from uid: " + uid);
        }
    }

    @Override
    public boolean hasCache(String uid) {
        if (stores.containsKey(uid)) {
            return !stores.get(uid).isEmpty();
        }
        Path vectorsFile = cacheDirectory.resolve(sanitizeFileName(uid) + ".bin");
        return Files.exists(vectorsFile);
    }

    @Override
    public CacheStats getStats() {
        int uidCount = stores.size();
        int totalChunks = stores.values().stream().mapToInt(VectorStore::size).sum();
        long cacheSize = 0;
        try {
            cacheSize = Files.walk(cacheDirectory)
                    .filter(p -> p.toFile().isFile())
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (Exception e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (Exception ignored) {}
        return new CacheStats(uidCount, totalChunks, cacheSize);
    }

    @Override
    public void clear() {
        stores.clear();
        try {
            Files.walk(cacheDirectory)
                    .filter(p -> p.toFile().isFile())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception ignored) {}
                    });
            env.info("[RAG] Cleared all caches and deleted files");
        } catch (Exception e) {
            env.error("[RAG] Failed to clear cache files", e);
        }
    }
}
