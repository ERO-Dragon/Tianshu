package com.rheinmetal.tianshu.client.ir;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.function.ir.core.IRCommandService;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.IRSnapshot;
import com.rheinmetal.tianshu.function.ir.core.ParseUnit;
import com.rheinmetal.tianshu.function.ir.enhance.IrContextHint;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Client-lifetime IR index with serialized asynchronous startup and resource-reload rebuilds. */
public final class ClientNamedObjectIndexManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final IRCommandService IR_SERVICE = new IRCommandService();
    private static final ClientItemDictionaryBuilder ITEM_DICTIONARY_BUILDER = new ClientItemDictionaryBuilder();
    private static final ClientEntityDictionaryBuilder ENTITY_DICTIONARY_BUILDER = new ClientEntityDictionaryBuilder();
    private static final IRCacheStore CACHE_STORE = new IRCacheStore();
    private static final ExecutorService INDEX_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Tianshu-IR-Index");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object TASK_MONITOR = new Object();

    private static volatile String lastBuildReason = "uninitialized";
    private static boolean closed;
    private static CompletableFuture<Boolean> initializationFuture;

    private ClientNamedObjectIndexManager() {
    }

    public static CompletableFuture<Boolean> initializeAsync(String reason) {
        if (IR_SERVICE.isReady()) {
            return CompletableFuture.completedFuture(true);
        }
        synchronized (TASK_MONITOR) {
            if (IR_SERVICE.isReady()) {
                return CompletableFuture.completedFuture(true);
            }
            if (initializationFuture != null && !initializationFuture.isDone()) {
                return initializationFuture;
            }
            initializationFuture = submitRebuild(reason, null);
            return initializationFuture;
        }
    }

    public static CompletableFuture<Boolean> reloadAsync(String reason, Runnable beforeRebuild) {
        return submitRebuild(reason, beforeRebuild);
    }

    private static boolean ensureIndex(String reason) {
        if (IR_SERVICE.isReady()) {
            return true;
        }
        initializeAsync(reason);
        return false;
    }

    private static int getIndexedObjectCount() {
        return IR_SERVICE.getIndexedObjectCount();
    }

    static String resolveDisplayName(String realItemId) {
        ensureIndex("display name resolve");
        return IR_SERVICE.resolveDisplayName(realItemId);
    }

    static String resolveEntityDisplayName(String entityTypeId) {
        ensureIndex("entity display name resolve");
        return IR_SERVICE.resolveEntityDisplayName(entityTypeId);
    }

    static IRParseResult parsePlayerCommand(String rawText, boolean isFastIR, IrContextHint contextHint) {
        awaitInitialization("parse fallback");
        return IR_SERVICE.parse(rawText, ClientItemContextResolver.from(contextHint), isFastIR);
    }

    public static void close() {
        synchronized (TASK_MONITOR) {
            if (closed) {
                return;
            }
            closed = true;
            INDEX_EXECUTOR.shutdownNow();
        }
    }

    private static CompletableFuture<Boolean> submitRebuild(String reason, Runnable beforeRebuild) {
        String buildReason = reason == null || reason.isBlank() ? "unspecified" : reason;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        synchronized (TASK_MONITOR) {
            if (closed) {
                result.completeExceptionally(new RejectedExecutionException("IR index manager is closed"));
                return result;
            }
            try {
                INDEX_EXECUTOR.execute(() -> runRebuild(buildReason, beforeRebuild, result));
            } catch (RejectedExecutionException failure) {
                result.completeExceptionally(failure);
            }
        }
        return result;
    }

    private static void runRebuild(
            String reason,
            Runnable beforeRebuild,
            CompletableFuture<Boolean> result
    ) {
        try {
            if (beforeRebuild != null) {
                beforeRebuild.run();
            }
            result.complete(rebuildIndexNow(reason));
        } catch (RuntimeException failure) {
            LOGGER.error("IR named object index rebuild failed, reason={}", reason, failure);
            result.complete(false);
        } catch (Error failure) {
            result.completeExceptionally(failure);
            throw failure;
        }
    }

    private static boolean rebuildIndexNow(String reason) {
        Map<String, List<String>> dictionary = buildDictionary();
        if (dictionary.isEmpty()) {
            LOGGER.warn("IR named object dictionary is empty, clear index, reason={}", reason);
            IR_SERVICE.clear();
            lastBuildReason = reason + " [empty]";
            return false;
        }

        String fingerprint = CACHE_STORE.buildFingerprint(dictionary);
        IRSnapshot cachedSnapshot = tryLoadSnapshot(fingerprint, reason);
        if (cachedSnapshot != null) {
            IR_SERVICE.restore(cachedSnapshot);
            lastBuildReason = reason + " [cache]";
            LOGGER.info(
                    "IR named object index loaded from cache, objects={}, reason={}, file={}",
                    getIndexedObjectCount(),
                    reason,
                    CACHE_STORE.cacheFilePath()
            );
            return true;
        }

        IR_SERVICE.rebuild(dictionary);
        trySaveSnapshot(fingerprint, reason);
        lastBuildReason = reason;
        LOGGER.info(
                "IR named object index rebuilt, objects={}, reason={}, file={}",
                getIndexedObjectCount(),
                reason,
                CACHE_STORE.cacheFilePath()
        );
        return true;
    }

    private static void awaitInitialization(String reason) {
        if (IR_SERVICE.isReady()) {
            return;
        }
        try {
            initializeAsync(reason).join();
        } catch (CompletionException failure) {
            LOGGER.error("IR named object index initialization failed, reason={}", reason, failure.getCause());
        }
    }

    private static IRSnapshot tryLoadSnapshot(String fingerprint, String reason) {
        try {
            IRSnapshot snapshot = CACHE_STORE.loadIfMatches(fingerprint);
            if (snapshot == null) {
                LOGGER.info("IR index cache miss, rebuild will continue, reason={}, file={}", reason, CACHE_STORE.cacheFilePath());
            }
            return snapshot;
        } catch (IOException exception) {
            LOGGER.warn("IR index cache read failed, rebuild will continue, reason={}, file={}", reason, CACHE_STORE.cacheFilePath(), exception);
            return null;
        }
    }

    private static void trySaveSnapshot(String fingerprint, String reason) {
        try {
            IRSnapshot snapshot = IR_SERVICE.snapshot(fingerprint);
            CACHE_STORE.save(snapshot);
        } catch (IOException exception) {
            LOGGER.warn("IR index cache write failed, reason={}, file={}", reason, CACHE_STORE.cacheFilePath(), exception);
        }
    }

    private static Map<String, List<String>> buildDictionary() {
        LinkedHashMap<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.putAll(ITEM_DICTIONARY_BUILDER.build());
        dictionary.putAll(ENTITY_DICTIONARY_BUILDER.build());
        return dictionary;
    }
}
