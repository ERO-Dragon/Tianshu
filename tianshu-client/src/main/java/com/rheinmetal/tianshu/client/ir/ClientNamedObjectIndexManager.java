package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.core.IRCommandService;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.IRSnapshot;
import com.rheinmetal.tianshu.function.ir.enhance.IrContextHint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Client-lifetime IR index. It is shared by all world sessions and owns one bounded worker. */
public final class ClientNamedObjectIndexManager implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(ClientNamedObjectIndexManager.class.getName());

    private final IRCommandService irService = new IRCommandService();
    private final NamedObjectDictionaryProvider dictionaryProvider;
    private final IRCacheStore cacheStore;
    private final ExecutorService indexExecutor;
    private final Object taskMonitor = new Object();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;
    private CompletableFuture<Boolean> initializationFuture;

    public ClientNamedObjectIndexManager(
            NamedObjectDictionaryProvider dictionaryProvider,
            Path cacheDirectory,
            Supplier<String> languageCodeSupplier
    ) {
        this.dictionaryProvider = Objects.requireNonNull(dictionaryProvider, "dictionaryProvider");
        this.cacheStore = new IRCacheStore(cacheDirectory, languageCodeSupplier);
        this.indexExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                task -> {
                    Thread thread = new Thread(task, "Tianshu-IR-Index");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<Boolean> initializeAsync(String reason) {
        if (closed) {
            return failedFuture(new RejectedExecutionException("IR index manager is closed"));
        }
        if (irService.isReady()) {
            return CompletableFuture.completedFuture(true);
        }
        synchronized (taskMonitor) {
            if (closed) {
                return failedFuture(new RejectedExecutionException("IR index manager is closed"));
            }
            if (irService.isReady()) {
                return CompletableFuture.completedFuture(true);
            }
            if (initializationFuture != null && !initializationFuture.isDone()) {
                return initializationFuture;
            }
            initializationFuture = submitRebuild(reason, null);
            return initializationFuture;
        }
    }

    public CompletableFuture<Boolean> reloadAsync(String reason, Runnable beforeRebuild) {
        synchronized (taskMonitor) {
            if (closed) {
                return failedFuture(new RejectedExecutionException("IR index manager is closed"));
            }
            return submitRebuild(reason, beforeRebuild);
        }
    }

    boolean isReady() {
        return irService.isReady();
    }

    int indexedObjectCount() {
        return irService.getIndexedObjectCount();
    }

    String resolveDisplayName(String realItemId) {
        ensureIndex("display name resolve");
        return irService.resolveDisplayName(realItemId);
    }

    String resolveEntityDisplayName(String entityTypeId) {
        ensureIndex("entity display name resolve");
        return irService.resolveEntityDisplayName(entityTypeId);
    }

    IRParseResult parsePlayerCommand(String rawText, boolean isFastIR, IrContextHint contextHint) {
        ensureIndex("parse fallback");
        return irService.parse(rawText, ClientItemContextResolver.from(contextHint), isFastIR);
    }

    @Override
    public void close() {
        synchronized (taskMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            generation.incrementAndGet();
            indexExecutor.shutdownNow();
        }
    }

    private CompletableFuture<Boolean> submitRebuild(String reason, Runnable beforeRebuild) {
        String buildReason = reason == null || reason.isBlank() ? "unspecified" : reason;
        long taskGeneration = generation.get();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            indexExecutor.execute(() -> runRebuild(buildReason, beforeRebuild, taskGeneration, result));
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void runRebuild(String reason, Runnable beforeRebuild, long taskGeneration, CompletableFuture<Boolean> result) {
        try {
            if (beforeRebuild != null) {
                beforeRebuild.run();
            }
            result.complete(rebuildIndexNow(reason, taskGeneration));
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.ERROR, "IR named object index rebuild failed, reason=" + reason, failure);
            result.complete(false);
        } catch (Error failure) {
            result.completeExceptionally(failure);
            throw failure;
        }
    }

    private boolean rebuildIndexNow(String reason, long taskGeneration) {
        Map<String, List<String>> dictionary = snapshotDictionary();
        if (!isGenerationActive(taskGeneration)) {
            return false;
        }
        if (dictionary.isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING, "IR named object dictionary is empty, clear index, reason=" + reason);
            irService.clear();
            return false;
        }

        String fingerprint = cacheStore.buildFingerprint(dictionary);
        IRSnapshot cachedSnapshot = tryLoadSnapshot(fingerprint, reason);
        if (cachedSnapshot != null) {
            if (!isGenerationActive(taskGeneration)) {
                return false;
            }
            irService.restore(cachedSnapshot);
            return true;
        }

        irService.rebuild(dictionary);
        if (isGenerationActive(taskGeneration)) {
            trySaveSnapshot(fingerprint, reason);
            return true;
        }
        return false;
    }

    private boolean isGenerationActive(long taskGeneration) {
        return !closed && generation.get() == taskGeneration;
    }

    private void ensureIndex(String reason) {
        if (!irService.isReady()) {
            initializeAsync(reason);
        }
    }

    private IRSnapshot tryLoadSnapshot(String fingerprint, String reason) {
        try {
            return cacheStore.loadIfMatches(fingerprint);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "IR index cache read failed, reason=" + reason, exception);
            return null;
        }
    }

    private void trySaveSnapshot(String fingerprint, String reason) {
        try {
            cacheStore.save(irService.snapshot(fingerprint));
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "IR index cache write failed, reason=" + reason, exception);
        }
    }

    private Map<String, List<String>> snapshotDictionary() {
        Map<String, List<String>> source = dictionaryProvider.snapshot();
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(source);
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }
}
