package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PersistentRagCacheManager implements RagCacheManager {

    private static final int DEFAULT_TOP_K = 4;
    private static final float DEFAULT_THRESHOLD = 0.7f;
    private static final int CACHE_MAGIC = 0x54535247; // TSRG
    private static final int CACHE_VERSION = 3;
    private static final int MAX_TEXT_BYTES = 1_048_576;
    private static final int MAX_ENTRY_ID_BYTES = 4_096;
    private static final int MAX_VECTOR_DIMENSION = 65_536;
    /** How many digest bytes the cache file name carries; 6 bytes is plenty for a per-install uid set. */
    private static final int DIGEST_HEX_BYTES = 6;
    private static final Duration FLUSH_DEBOUNCE = Duration.ofSeconds(1);

    private final IGameEnvironment env;
    private final EmbeddingService embeddingService;
    private final Path cacheDirectory;
    private final String namespace;
    private final Executor ragSearchExecutor;
    private final RagPersistenceScheduler ragPersistenceScheduler;
    private final ConcurrentMap<String, VectorStore> stores = new ConcurrentHashMap<>();
    private final Set<String> dirtyUids = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public PersistentRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService, Path cacheDirectory) {
        this(env, embeddingService, cacheDirectory, "default");
    }

    public PersistentRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService, Path cacheDirectory, String namespace) {
        this(env, embeddingService, cacheDirectory, namespace, Runnable::run);
    }

    public PersistentRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService, Path cacheDirectory, String namespace, Executor ragSearchExecutor) {
        this(env, embeddingService, cacheDirectory, namespace, ragSearchExecutor, RagPersistenceScheduler.immediate());
    }

    public PersistentRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService, Path cacheDirectory, String namespace, Executor ragSearchExecutor, RagPersistenceScheduler ragPersistenceScheduler) {
        this.env = Objects.requireNonNull(env, "env");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService");
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.namespace = namespace == null || namespace.isBlank() ? "default" : namespace.trim();
        this.ragSearchExecutor = ragSearchExecutor == null ? Runnable::run : ragSearchExecutor;
        this.ragPersistenceScheduler = ragPersistenceScheduler == null ? RagPersistenceScheduler.immediate() : ragPersistenceScheduler;
        initialize();
    }

    @Override
    public void upsert(String uid, String entryId, String content, float[] vector) {
        if (isBlank(uid) || isBlank(entryId)) {
            return;
        }
        try {
            VectorStore store = stores.computeIfAbsent(uid.trim(), VectorStore::new);
            if (isSameContentWithoutVectorChange(store, entryId, content, vector)) {
                return;
            }
            float[] effectiveVector = normalizeVector(resolveVector(content, vector));
            if (store.upsert(entryId, content, effectiveVector)) {
                markDirty(uid);
                env.info("[RAG] Upserted entry for uid: " + uid);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to upsert entry for uid: " + uid, e);
        }
    }

    @Override
    public void patch(String uid, String entryId, String content, float[] vector, boolean updateContent, boolean updateVector) {
        if (isBlank(uid) || isBlank(entryId)) {
            return;
        }
        try {
            VectorStore store = stores.computeIfAbsent(uid.trim(), VectorStore::new);
            float[] effectiveVector = updateVector ? normalizeVector(resolveVector(content, vector)) : null;
            if (store.patch(entryId, content, effectiveVector, updateContent, updateVector)) {
                markDirty(uid);
                env.info("[RAG] Patched entry for uid: " + uid);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to patch entry for uid: " + uid, e);
        }
    }

    @Override
    public void deleteEntry(String uid, String entryId) {
        if (isBlank(uid) || isBlank(entryId)) {
            return;
        }
        loadStoreIfNeeded(uid);
        VectorStore store = stores.get(uid.trim());
        if (store != null && store.deleteEntry(entryId)) {
            markDirty(uid);
            env.info("[RAG] Deleted entry from uid: " + uid);
        }
    }

    @Override
    public void clearUid(String uid) {
        if (isBlank(uid)) {
            return;
        }
        String cleanUid = uid.trim();
        stores.remove(cleanUid);
        dirtyUids.remove(cleanUid);
        deleteStoreFile(cleanUid);
        updateManifest(cleanUid, false);
        env.info("[RAG] Cleared uid: " + cleanUid);
    }

    @Override
    public boolean hasEntry(String uid, String entryId) {
        if (isBlank(uid) || isBlank(entryId)) {
            return false;
        }
        loadStoreIfNeeded(uid);
        VectorStore store = stores.get(uid.trim());
        return store != null && store.hasEntry(entryId);
    }

    @Override
    public List<RagEntrySearchResult> searchEntries(String uid, String queryText, int topK, float threshold) {
        return searchEntries(uid, queryText, embedQueryVector(queryText), topK, threshold);
    }

    @Override
    public List<RagEntrySearchResult> searchEntries(String uid, String queryText, float[] queryVector, int topK, float threshold) {
        if (isBlank(uid) || isBlank(queryText)) {
            return List.of();
        }
        try {
            loadStoreIfNeeded(uid);
            VectorStore store = stores.get(uid.trim());
            if (store == null || store.isEmpty()) {
                return List.of();
            }
            int effectiveTopK = topK > 0 ? topK : DEFAULT_TOP_K;
            float effectiveThreshold = threshold > 0f && threshold <= 1f ? threshold : DEFAULT_THRESHOLD;
            CompletableFuture<Map<String, Double>> bm25Scores = bm25ScoresAsync(store, queryText);
            return store.searchEntries(normalizeVector(queryVector), bm25Scores.join(), effectiveTopK, effectiveThreshold);
        } catch (Exception e) {
            env.error("[RAG] Failed to search for uid: " + uid, e);
            return List.of();
        }
    }

    @Override
    public List<RagSearchResult> search(String uid, String queryText, int topK, float threshold) {
        return searchEntries(uid, queryText, topK, threshold).stream()
                .filter(result -> !result.content().isBlank())
                .map(result -> new RagSearchResult(result.content(), result.score()))
                .toList();
    }

    @Override
    public boolean hasCache(String uid) {
        if (isBlank(uid)) {
            return false;
        }
        loadStoreIfNeeded(uid);
        VectorStore store = stores.get(uid.trim());
        return store != null && !store.isEmpty();
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
    public void flush() {
        flushDirtyUids();
    }

    @Override
    public void clear() {
        stores.clear();
        dirtyUids.clear();
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

    private void loadStoreIfNeeded(String uid) {
        String cleanUid = uid == null ? "" : uid.trim();
        if (!cleanUid.isBlank() && !stores.containsKey(cleanUid) && existingVectorsFile(cleanUid) != null) {
            loadStore(cleanUid);
        }
    }

    /**
     * Loads one store from disk, preferring the current file name and falling back to the legacy one.
     *
     * <p>The file is fully read before anything is deleted: Windows refuses to delete a file that is
     * still open, so a corrupt cache used to survive its own cleanup and be re-read every session. A
     * store read from the legacy name is marked dirty, which is what migrates it to the current name on
     * the next flush.</p>
     */
    private void loadStore(String uid) {
        if (isBlank(uid)) {
            return;
        }
        String cleanUid = uid.trim();
        Path current = vectorsFile(cleanUid);
        Path source = existingVectorsFile(cleanUid);
        if (source == null) {
            return;
        }
        boolean legacy = !source.equals(current);

        VectorStore store = new VectorStore(cleanUid);
        boolean rejected = false;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            int magic = dis.readInt();
            int version = dis.readInt();
            if (magic != CACHE_MAGIC || version != CACHE_VERSION) {
                env.warn("[RAG] Ignored incompatible cache file for uid: " + uid);
                rejected = true;
            } else {
                String fileNamespace = dis.readUTF();
                int count = dis.readInt();
                if (!namespace.equals(fileNamespace) || count < 0) {
                    env.warn("[RAG] Ignored invalid cache metadata for uid: " + uid);
                    rejected = true;
                } else {
                    for (int entry = 0; entry < count; entry++) {
                        String entryId = readString(dis, MAX_ENTRY_ID_BYTES, false);
                        String content = readString(dis, MAX_TEXT_BYTES, true);
                        int vectorLength = dis.readInt();
                        if (vectorLength < 0 || vectorLength > MAX_VECTOR_DIMENSION) {
                            throw new IOException("Invalid vector dimension: " + vectorLength);
                        }
                        float[] vector = new float[vectorLength];
                        for (int i = 0; i < vectorLength; i++) {
                            float value = dis.readFloat();
                            if (Float.isNaN(value) || Float.isInfinite(value)) {
                                throw new IOException("Invalid vector value");
                            }
                            vector[i] = value;
                        }
                        store.upsert(entryId, content, vectorLength == 0 ? null : vector);
                    }
                }
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to load store for uid: " + uid, e);
            discardUnreadableStore(cleanUid, source);
            return;
        }

        if (rejected) {
            discardUnreadableStore(cleanUid, source);
            return;
        }
        if (store.isEmpty()) {
            return;
        }
        stores.put(cleanUid, store);
        if (legacy) {
            // A store read from the old name is rewritten under the current one, which is what makes the
            // rename a one-time migration instead of a second read every session.
            markDirty(cleanUid);
        }
    }

    private boolean saveStore(String uid) {
        String cleanUid = uid == null ? "" : uid.trim();
        VectorStore store = stores.get(cleanUid);
        if (store == null || store.isEmpty()) {
            deleteStoreFile(cleanUid);
            updateManifest(cleanUid, false);
            return true;
        }

        List<VectorStore.EntrySnapshot> entries = store.getEntries();
        try {
            Files.createDirectories(cacheDirectory);
            Path tmp = cacheDirectory.resolve(fileName(cleanUid) + ".tmp");
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                dos.writeInt(CACHE_MAGIC);
                dos.writeInt(CACHE_VERSION);
                dos.writeUTF(namespace);
                dos.writeInt(entries.size());
                for (VectorStore.EntrySnapshot entry : entries) {
                    writeString(dos, entry.entryId());
                    writeString(dos, entry.content());
                    float[] vector = entry.vector();
                    if (vector == null) {
                        dos.writeInt(0);
                    } else {
                        if (!isUsableVector(vector)) {
                            throw new IOException("Invalid vector while saving uid: " + uid);
                        }
                        dos.writeInt(vector.length);
                        for (float value : vector) {
                            dos.writeFloat(value);
                        }
                    }
                }
            }
            moveIntoPlace(tmp, vectorsFile(cleanUid));
            updateManifest(cleanUid, true);
            deleteLegacyIfPresent(cleanUid);
            return true;
        } catch (Exception e) {
            env.error("[RAG] Failed to save store for uid: " + uid, e);
            return false;
        }
    }

    private void markDirty(String uid) {
        String cleanUid = uid == null ? "" : uid.trim();
        if (cleanUid.isBlank()) {
            return;
        }
        dirtyUids.add(cleanUid);
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            ragPersistenceScheduler.schedule(this::runScheduledFlush, FLUSH_DEBOUNCE);
        } catch (RejectedExecutionException e) {
            flushScheduled.set(false);
            flushDirtyUids();
        }
    }

    private void runScheduledFlush() {
        try {
            flushDirtyUids();
        } finally {
            flushScheduled.set(false);
            if (!dirtyUids.isEmpty()) {
                scheduleFlush();
            }
        }
    }

    private void flushDirtyUids() {
        Set<String> snapshot = new LinkedHashSet<>(dirtyUids);
        if (snapshot.isEmpty()) {
            return;
        }
        dirtyUids.removeAll(snapshot);
        for (String uid : snapshot) {
            if (!saveStore(uid)) {
                dirtyUids.add(uid);
            }
        }
    }

    private float[] resolveVector(String content, float[] vector) throws Exception {
        if (isUsableVector(vector)) {
            return vector;
        }
        if (content == null || content.isBlank()) {
            return null;
        }
        return embeddingService.embed(content);
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
        if (isBlank(uid)) {
            return;
        }
        try {
            Set<String> uids = readManifest();
            boolean changed = add ? uids.add(uid.trim()) : uids.remove(uid.trim());
            if (!changed) {
                return;
            }
            Files.createDirectories(cacheDirectory);
            // Written to a temporary file and moved into place: a crash mid-write would otherwise leave a
            // truncated manifest, and the manifest is the only index of which stores exist.
            Path tmp = cacheDirectory.resolve("manifest.txt.tmp");
            Files.write(tmp, uids, StandardCharsets.UTF_8);
            moveIntoPlace(tmp, manifestFile());
        } catch (Exception e) {
            env.error("[RAG] Failed to update manifest", e);
        }
    }

    private Path manifestFile() {
        return cacheDirectory.resolve("manifest.txt");
    }

    /**
     * The cache file of one library.
     *
     * <p>The readable part of the name is sanitized, which is not injective: two different uids can
     * collapse onto the same string (`mod:a` and `mod_a`, or any non-ASCII uid). A digest of the raw
     * uid follows it so each library keeps its own file instead of silently overwriting another one's.</p>
     */
    private Path vectorsFile(String uid) {
        return cacheDirectory.resolve(fileName(uid) + ".bin");
    }

    /** The name this build used before the digest existed: read once, then migrated. */
    private Path legacyVectorsFile(String uid) {
        return cacheDirectory.resolve(sanitizeFileName(uid) + ".bin");
    }

    private Path existingVectorsFile(String uid) {
        Path current = vectorsFile(uid);
        if (Files.isRegularFile(current)) {
            return current;
        }
        Path legacy = legacyVectorsFile(uid);
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    private String fileName(String uid) {
        String cleanUid = uid == null ? "" : uid.trim();
        return sanitizeFileName(cleanUid) + "-" + digest(cleanUid);
    }

    /** Short stable digest of the raw uid; no security property is claimed, only uniqueness. */
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(DIGEST_HEX_BYTES * 2);
            for (int index = 0; index < DIGEST_HEX_BYTES; index++) {
                hex.append(Character.forDigit((bytes[index] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(bytes[index] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            // Every JVM ships SHA-256, so this is a broken runtime rather than a case to fail a write on.
            return Integer.toHexString(value.hashCode());
        }
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
            Files.deleteIfExists(legacyVectorsFile(uid));
        } catch (Exception e) {
            env.error("[RAG] Failed to delete cache file for uid: " + uid, e);
        }
    }

    /** Removes the pre-digest file once the store lives under the current name; a no-op for new stores. */
    private void deleteLegacyIfPresent(String uid) {
        Path legacy = legacyVectorsFile(uid);
        if (legacy.equals(vectorsFile(uid))) {
            return;
        }
        try {
            Files.deleteIfExists(legacy);
        } catch (Exception e) {
            env.warn("[RAG] Failed to remove the legacy cache file for uid: " + uid);
        }
    }

    /**
     * Drops one unreadable store and only the file it was read from.
     *
     * <p>Deleting both names would throw away a legacy file that the current name shadowed, and that
     * file may be the readable copy.</p>
     */
    private void discardUnreadableStore(String uid, Path source) {
        stores.remove(uid == null ? "" : uid.trim());
        try {
            Files.deleteIfExists(source);
        } catch (Exception e) {
            env.error("[RAG] Failed to delete the unreadable cache file for uid: " + uid, e);
        }
        if (!Files.isRegularFile(vectorsFile(uid))) {
            updateManifest(uid, false);
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "empty";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void writeString(DataOutputStream dos, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private static String readString(DataInputStream dis, int maxBytes, boolean allowEmpty) throws IOException {
        int length = dis.readInt();
        if (length < 0 || length > maxBytes || (!allowEmpty && length == 0)) {
            throw new IOException("Invalid cached string size: " + length);
        }
        byte[] bytes = dis.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated cached string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static float[] normalizeVector(float[] vector) {
        return isUsableVector(vector) ? VectorMath.normalize(vector) : null;
    }

    private float[] embedQueryVector(String queryText) {
        try {
            return embeddingService.embed(queryText);
        } catch (Exception e) {
            env.error("[RAG] Failed to embed query text", e);
            return null;
        }
    }

    private CompletableFuture<Map<String, Double>> bm25ScoresAsync(VectorStore store, String queryText) {
        try {
            return CompletableFuture.supplyAsync(() -> store.bm25Scores(queryText), ragSearchExecutor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(store.bm25Scores(queryText));
        }
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isSameContentWithoutVectorChange(VectorStore store, String entryId, String content, float[] vector) {
        if (isUsableVector(vector)) {
            return false;
        }
        VectorStore.EntrySnapshot existing = store.getEntry(entryId);
        if (existing == null || !isUsableVector(existing.vector())) {
            return false;
        }
        String nextContent = content == null ? "" : content.trim();
        return existing.content().equals(nextContent);
    }
}
