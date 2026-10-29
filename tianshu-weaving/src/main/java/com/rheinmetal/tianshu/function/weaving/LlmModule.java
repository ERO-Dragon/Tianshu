package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.weaving.account.DeepSeekBalanceProbe;
import com.rheinmetal.tianshu.function.weaving.catalog.OpenAiCompatibleModelCatalogClient;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingContextTiers;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.function.weaving.rag.LlmRagCacheLayout;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeRetryPolicy;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.weaving.service.EmbeddingLoadReport;
import com.rheinmetal.tianshu.function.weaving.service.EmbeddingService;
import com.rheinmetal.tianshu.function.weaving.service.LLMService;
import com.rheinmetal.tianshu.function.weaving.service.LlmRagStorageService;
import com.rheinmetal.tianshu.function.weaving.service.OpenAiCompatibleInferenceClient;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.function.weaving.contract.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.function.weaving.bridge.LlmPresenceActivityBridge;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LlmModule implements TianshuManagedModule {
    private static final List<RuntimeCapability> PROVIDED_CAPABILITIES = List.of(
            LlmRuntimeCapabilities.LLM_REQUEST,
            LlmRuntimeCapabilities.LLM_CACHE_MANAGE,
            LlmRuntimeCapabilities.LLM_PRIMITIVE_QUERY
    );
    /** Cloud settings a request needs; used verbatim in the diagnostics, never in player-facing text. */
    private static final String REMOTE_SETTING_API_KEY = "remoteApiKey";
    private static final String REMOTE_SETTING_BASE_URL = "remoteBaseUrl";
    private static final String REMOTE_SETTING_MODEL = "remoteModel";
    private static final String STATUS_KEY_MISSING_API_KEY = "tianshu.runtime.module.llm.missing_api_key";
    private static final String STATUS_KEY_REMOTE_INCOMPLETE = "tianshu.runtime.module.llm.remote_incomplete";
    /** How long the reconnect line stays up past the wait it announces. */
    private static final long RETRY_STATUS_TAIL_MILLIS = 2_000L;
    /** How long presence keeps the failure line, so a player who looks up still sees it. */
    private static final long RUNTIME_ERROR_TTL_MILLIS = 12_000L;
    /**
     * Quiet time between the world coming up and the proactive embedding load.
     *
     * <p>The module starts on every world session, so an immediate warm-up would run a native model load —
     * a few hundred megabytes mapped plus a backend initialisation — on top of chunk loading and the first
     * frames. Six seconds is long enough for the entry burst to pass on a laptop-class machine. This only
     * postpones the proactive load: a RAG write that arrives sooner still loads the model on demand, on the
     * lane it already runs on, and a module that stops inside the window simply cancels the queued load.</p>
     */
    private static final Duration EMBEDDING_LOAD_DELAY_AFTER_WORLD_ENTRY = Duration.ofSeconds(6);

    private final IGameEnvironment env;
    private final WeavingConfiguration config;
    private final ModuleRuntimeAccess runtime;
    private final LlmRagCacheLayout ragCacheLayout;
    private final LlmRagStorageService ragStorageService;
    private final LlmExecutor llmExecutor;
    private final LlmEngineProvider engineProvider;
    private final LlmProtocolAdapter adapter;
    /**
     * Token accounting for every request this module serves. The module owns it and injects it, so
     * the request path records into the same instance the HUD reads through {@link LlmModuleService};
     * there is no static lookup anywhere in the chain.
     */
    private final LlmUsageLedger usageLedger = new LlmUsageLedger();
    private final Object lifecycleLock = new Object();
    private LLMService llmService;
    private ModuleRuntimeContext runtimeContext;
    private LlmModuleService moduleService;
    private LlmModelService modelService;
    private ModuleServiceRegistry serviceRegistry;
    private volatile OpenAiCompatibleInferenceClient remoteClient;
    private boolean destroyed;
    /** Process-wide serial gate for native model loads; null keeps this module self-contained. */
    private final NativeModelLoadQueue loadQueue;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile ProtocolTaskHandle delayedAutoLoad;
    /** Retries already scheduled for the current start failure; reset when the lane comes up. */
    private final AtomicInteger runtimeRetryAttempts = new AtomicInteger();
    private volatile ProtocolTaskHandle pendingRuntimeRetry;
    /** The actionable key of the last start failure; reported verbatim once the retries are spent. */
    private volatile String lastFailureStatusKey = STATUS_KEY_MISSING_API_KEY;
    private volatile long lifecycleStatusGeneration;
    private volatile boolean lifecycleActive;

    public LlmModule(IGameEnvironment env, WeavingConfiguration config, ModuleRuntimeAccess runtime) {
        this(env, config, runtime, null);
    }

    public LlmModule(IGameEnvironment env, WeavingConfiguration config, ModuleRuntimeAccess runtime,
                     NativeModelLoadQueue loadQueue) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
        this.loadQueue = loadQueue;
        this.ragCacheLayout = new LlmRagCacheLayout(config);
        this.llmExecutor = new LlmExecutor(runtime);
        this.ragStorageService = new LlmRagStorageService(
                env,
                ragCacheLayout.cacheDirectory(),
                ragCacheLayout.cacheNamespace(),
                true,
                llmExecutor.cpuExecutor(),
                llmExecutor.ragPersistenceScheduler()
        );
        this.adapter = new LlmProtocolAdapter(runtime, null, LlmTaskAdmissionController.fromConfig(config), usageLedger);
        this.adapter.setRagStorageService(ragStorageService);
        this.adapter.setUnavailableRuntimeSnapshotSupplier(this::runtimeSnapshotWhenGenerationUnavailable);
        this.engineProvider = new LlmEngineProvider(env, config,
                llmExecutor.delayedModelLoadExecutor(EMBEDDING_LOAD_DELAY_AFTER_WORLD_ENTRY),
                this::onEmbeddingLoadSettled,
                loadQueue);
    }

    @Override
    public String moduleId() {
        return ProtocolModuleIds.LLM;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        serviceRegistry = context == null ? null : context.services();
        moduleService = new LlmModuleService(config, usageLedger);
        // The service owns the runtime state; the module owns what the HUD is told about it. Switching the
        // module off has to end with a visible "stopped", otherwise the last status — usually "starting",
        // which never expires — is what the player keeps seeing on screen.
        moduleService.onStateChanged(state -> {
            if (state == com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeState.STOPPED
                    || state == com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeState.DISABLED) {
                publishStoppedStatus();
            } else if (state == com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeState.FAILED) {
                publishModuleStatus(ModuleStatuses.lifecycleFailed(
                        moduleId(), "tianshu.runtime.module.llm.failed", lifecycleStatusGeneration));
            }
        });
        moduleService.bindRuntimeController(new LlmModuleService.RuntimeController() {
            @Override
            public void start() {
                startRuntime();
            }

            @Override
            public void stop() {
                stopRuntime();
            }
        });
        // The provider model-list endpoint is the only network call the settings page can trigger;
        // binding it here keeps the page free of transport code and lets a test double replace it.
        moduleService.bindModelCatalogClient(new OpenAiCompatibleModelCatalogClient(env::warn));
        // The balance endpoint is the provider's account API. Same rule: the page and the HUD read a
        // snapshot, this module owns the request, and tests can replace the probe the same way.
        moduleService.bindAccountProbe(new DeepSeekBalanceProbe(config, env::warn));
        modelService = new LlmModelService(env, config, runtime, this::publishModuleStatus);
        if (serviceRegistry != null) {
            serviceRegistry.register(LlmModuleService.class, moduleService);
            serviceRegistry.register(LlmModelService.class, modelService);
        }
        adapter.registerLLMRequestCapability(this::handleLLMRequest);
        adapter.registerLLMCacheManageCapability(this::handleLLMCacheManage);
        adapter.registerLLMPrimitiveQueryCapability(this::handleLLMPrimitiveQuery);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        lifecycleStatusGeneration++;
        lifecycleActive = true;
        runtimeContext = context;
        markCapabilitiesInstalled(context);
        if (config.isLlmEnabled()) {
            publishModuleStatus(ModuleStatuses.lifecycleStarting(moduleId(), lifecycleStatusGeneration));
            markGenerationCapabilityFailed("LLM model is not loaded");
            markStorageCapabilitiesReady();
            if (moduleService != null) {
                moduleService.markStopped();
            }
        } else {
            // Switched off is a resting state, not a failure: the HUD says "stopped" and stays quiet.
            disableCapabilities();
            if (moduleService != null) {
                moduleService.markStopped();
            }
            publishStoppedStatus();
        }
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (!config.isLlmEnabled()) {
            return;
        }
        // Announced before the module schedules anything: the retrieval load owns the native-load gate
        // from world entry on, so a speech model that is ready sooner still waits for retrieval.
        if (loadQueue != null) {
            loadQueue.claimPrimary();
        }
        long generation = lifecycleGeneration.incrementAndGet();
        delayedAutoLoad = runtime.schedule(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId())
                        .lane(ExecutionLane.SCHEDULED)
                        .concurrencyKey("module.llm:auto-load")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .interruptible(true)
                        .build(),
                () -> {
                    if (generation != lifecycleGeneration.get() || destroyed) {
                        return;
                    }
                    LlmModuleService service = moduleService;
                    if (service != null) {
                        service.load();
                    }
                },
                Duration.ofMillis(Math.max(0L, config.getLlmAutoLoadDelayMillis()))
        );
    }

    @Override
    public void stop() {
        stopRuntime();
        // A load that was queued but never reached its lane must not keep the gate into the next world.
        engineProvider.abandonQueuedLoad();
    }

    @Override
    public void destroy() {
        destroyed = true;
        stop();
        engineProvider.stop();
        if (runtimeContext != null) {
            PROVIDED_CAPABILITIES.forEach(runtimeContext.runtimeState().capabilities()::remove);
        }
        runtimeContext = null;
    }

    private void handleLLMRequest(TianshuEnvelope envelope, ProtocolContext context) {
        adapter.handleLLMRequest(envelope, context);
    }

    private void handleLLMCacheManage(TianshuEnvelope envelope, ProtocolContext context) {
        adapter.handleLLMCacheManage(envelope, context);
    }

    private void handleLLMPrimitiveQuery(TianshuEnvelope envelope, ProtocolContext context) {
        adapter.handleLLMPrimitiveQuery(envelope, context);
    }

    private void markCapabilitiesInstalled(ModuleRuntimeContext context) {
        PROVIDED_CAPABILITIES.forEach(capability -> context.runtimeState().capabilities().install(capability, moduleId()));
    }

    private void markCapabilitiesReady() {
        ModuleRuntimeContext context = runtimeContext;
        if (context == null) {
            return;
        }
        PROVIDED_CAPABILITIES.forEach(capability -> context.runtimeState().capabilities().markReady(capability, moduleId()));
    }

    private void markGenerationCapabilityFailed(String reason) {
        ModuleRuntimeContext context = runtimeContext;
        if (context != null) {
            context.runtimeState().capabilities().markFailed(
                    LlmRuntimeCapabilities.LLM_REQUEST,
                    moduleId(),
                    reason
            );
        }
    }

    private void markStorageCapabilitiesReady() {
        ModuleRuntimeContext context = runtimeContext;
        if (context != null) {
            context.runtimeState().capabilities().markReady(LlmRuntimeCapabilities.LLM_CACHE_MANAGE, moduleId());
            context.runtimeState().capabilities().markReady(LlmRuntimeCapabilities.LLM_PRIMITIVE_QUERY, moduleId());
        }
    }

    private void markCapabilitiesFailed(String reason) {
        ModuleRuntimeContext context = runtimeContext;
        if (context == null) {
            return;
        }
        PROVIDED_CAPABILITIES.forEach(capability -> context.runtimeState().capabilities().markFailed(capability, moduleId(), reason));
    }

    private void disableCapabilities() {
        ModuleRuntimeContext context = runtimeContext;
        if (context == null) {
            return;
        }
        PROVIDED_CAPABILITIES.forEach(capability -> context.runtimeState().capabilities().disable(capability, moduleId()));
    }

    private void startRuntime() {
        long generation = lifecycleGeneration.get();
        synchronized (lifecycleLock) {
            if (destroyed) {
                throw new IllegalStateException("LLM module is destroyed");
            }
            if (llmService != null && llmService.isReady()) {
                if (moduleService != null) {
                    moduleService.markReady();
                }
                markCapabilitiesReady();
                return;
            }
        }

        publishModuleStatus(ModuleStatuses.startingKeyed(moduleId(), "tianshu.runtime.module.llm.starting"));
        // From the player's side the cloud lane is loading until its request capability is usable; the notice also
        // keeps module keys unavailable while the start is in flight.
        adapter.publishLoadingActivity(true);
        // Cloud is the only chat lane. There is no local fallback: an unconfigured cloud endpoint
        // fails the lane and tells the player what to fill in, instead of quietly loading a GGUF.
        startRemoteRuntime(generation);
    }

    /**
     * The cloud lane builds its inference client straight from configuration, so no chat GGUF is
     * mapped and no chat model is downloaded. Vector RAG keeps running locally: the embedding engine
     * is started in embedding-only mode, which is why a cloud user still gets retrieval without ever
     * owning a chat model.
     */
    private void startRemoteRuntime(long generation) {
        String missingSetting = missingRemoteSetting();
        if (!missingSetting.isEmpty()) {
            markRemoteFailure(missingSetting);
            return;
        }
        OpenAiCompatibleInferenceClient client;
        try {
            client = createRemoteClient();
        } catch (RuntimeException invalidConfiguration) {
            env.warn("[LLM] Remote inference client rejected its configuration: " + invalidConfiguration.getMessage());
            client = null;
        }
        if (client == null) {
            markRemoteFailure(REMOTE_SETTING_API_KEY);
            return;
        }
        boolean embeddingReady = engineProvider.startEmbeddingOnly();
        synchronized (lifecycleLock) {
            if (destroyed || generation != lifecycleGeneration.get() || moduleService == null
                    || moduleService.snapshot().state() != LlmRuntimeState.STARTING) {
                client.shutdown();
                return;
            }
            OpenAiCompatibleInferenceClient replaced = remoteClient;
            remoteClient = client;
            if (replaced != null) {
                replaced.shutdown();
            }
            // Only the embedding side of the local engine is live here, so vector RAG keeps writing
            // and searching while generation itself is remote.
            EmbeddingService embeddingService = embeddingReady ? engineProvider.embeddingService() : null;
            ragStorageService.bindEmbeddingService(embeddingService);
            LLMService service = LLMService.builder()
                    .env(env)
                    .config(config)
                    .inferenceClient(client)
                    .usePersistentCache(true)
                    .cacheDirectory(ragCacheLayout.cacheDirectory())
                    .cacheNamespace(ragCacheLayout.cacheNamespace())
                    .embeddingConfigured(embeddingService != null)
                    .ragStorage(ragStorageService)
                    .ragSearchExecutor(llmExecutor.cpuExecutor())
                    .ragPersistenceScheduler(llmExecutor.ragPersistenceScheduler())
                    .build();
            llmService = service;
            if (serviceRegistry != null) {
                serviceRegistry.register(LLMService.class, service);
            }
            adapter.setLlmService(service);
            markCapabilitiesReady();
            moduleService.markReady();
        }
        publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.runtime.module.llm.ready"));
        publishModuleStatus(ModuleStatuses.lifecycleReady(moduleId(), lifecycleStatusGeneration));
        adapter.publishLoadingActivity(false);
        clearRuntimeRetry();
    }

    /** The first cloud setting a request would need, or an empty string when the lane is complete. */
    private String missingRemoteSetting() {
        if (isBlank(config.getLlmRemoteApiKey())) {
            return REMOTE_SETTING_API_KEY;
        }
        if (isBlank(config.getLlmRemoteBaseUrl())) {
            return REMOTE_SETTING_BASE_URL;
        }
        if (isBlank(config.getLlmRemoteModel())) {
            return REMOTE_SETTING_MODEL;
        }
        return "";
    }

    private OpenAiCompatibleInferenceClient createRemoteClient() {
        String baseUrl = config.getLlmRemoteBaseUrl();
        String apiKey = config.getLlmRemoteApiKey();
        String model = config.getLlmRemoteModel();
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            return null;
        }
        int windowTokens = config.getLlmContextWindowTokens();
        int tier = WeavingContextTiers.downgradeToLargestAvailable(config.getLlmContextTier(), windowTokens);
        if (tier != config.getLlmContextTier()) {
            env.warn("[LLM] Context tier downgraded to fit the declared window: requested="
                    + config.getLlmContextTier() + " window=" + windowTokens + " effective=" + tier);
        }
        return new OpenAiCompatibleInferenceClient(
                baseUrl,
                apiKey,
                model,
                Duration.ofSeconds(Math.max(1, config.getLlmRequestTimeoutSeconds())),
                env::warn,
                tier
        );
    }

    /**
     * Fails the lane with an actionable status key so the player is told to fill in the credential.
     * The credential itself never appears in a log, a diagnostic or a status line.
     *
     * <p>This is not yet a final failure: the lane schedules a retry, so the player-facing "failed" status
     * is published only once the attempts are spent. Until then presence shows the retry line, because a
     * transient outage is not something the player has to act on.
     */
    private void markRemoteFailure(String missingSetting) {
        String statusKey = REMOTE_SETTING_API_KEY.equals(missingSetting)
                ? STATUS_KEY_MISSING_API_KEY
                : STATUS_KEY_REMOTE_INCOMPLETE;
        env.warn("[LLM] Cloud LLM lane is not configured: missing " + missingSetting);
        markGenerationCapabilityFailed("Cloud LLM is not configured: missing " + missingSetting);
        markStorageCapabilitiesReady();
        if (moduleService != null) {
            moduleService.markFailed(statusKey);
        }
        lastFailureStatusKey = statusKey;
        adapter.publishLoadingActivity(false);
        scheduleRuntimeRetry();
    }

    /**
     * Schedules one more start attempt with the backoff this build ships, or reports the failure when the
     * attempts are used up.
     *
     * <p>The attempt runs on the runtime's scheduled lane, so a retry can never race the start it follows:
     * it is a separate task that only runs after the wait, and it exits immediately when the module was
     * stopped or restarted in the meantime. Nothing here blocks a thread — the wait is the scheduler's.
     */
    private void scheduleRuntimeRetry() {
        int attempt = runtimeRetryAttempts.incrementAndGet();
        if (!LlmRuntimeRetryPolicy.canRetry(attempt - 1)) {
            reportRuntimeRetryExhausted();
            return;
        }
        long delayMillis = LlmRuntimeRetryPolicy.delayMillisForAttempt(attempt);
        long generation = lifecycleGeneration.get();
        env.warn("[LLM] Cloud lane start failed; retry " + attempt + "/" + LlmRuntimeRetryPolicy.MAX_ATTEMPTS
                + " in " + delayMillis + "ms");
        adapter.publishRuntimeActivity(LlmPresenceActivityBridge.Kind.RECONNECTING, delayMillis + RETRY_STATUS_TAIL_MILLIS);
        cancelPendingRuntimeRetry();
        pendingRuntimeRetry = runtime.schedule(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId())
                        .lane(ExecutionLane.SCHEDULED)
                        .concurrencyKey("module.llm:runtime-retry")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .interruptible(true)
                        .build(),
                () -> {
                    if (destroyed || generation != lifecycleGeneration.get()) {
                        return;
                    }
                    LlmModuleService service = moduleService;
                    if (service != null) {
                        service.load();
                    }
                },
                Duration.ofMillis(delayMillis)
        );
    }

    /**
     * Hands the failure to presence once the retries are spent.
     *
     * <p>Presence owns the player-facing channel, so this publishes the actionable setting status for the
     * bookkeeping and one runtime activity of type {@code ERROR} — the same activity topic the thinking and
     * responding states use, which is what turns the HUD line into readable error text. A quiet warning
     * line goes to the log for the developer.
     */
    private void reportRuntimeRetryExhausted() {
        String failureKey = lastFailureStatusKey;
        env.warn("[LLM] Cloud lane did not start after " + LlmRuntimeRetryPolicy.MAX_ATTEMPTS
                + " retries; reporting to presence");
        publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), failureKey));
        publishModuleStatus(ModuleStatuses.lifecycleFailed(moduleId(), failureKey, lifecycleStatusGeneration));
        // Retrying stopped, so "reconnecting" is no longer true; the failure replaces it.
        adapter.publishLoadingActivity(false);
        adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.RECONNECTING);
        adapter.publishRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR, RUNTIME_ERROR_TTL_MILLIS);
    }

    /** A lane that came up clears the retry budget, so the next failure starts from the first attempt. */
    private void clearRuntimeRetry() {
        runtimeRetryAttempts.set(0);
        cancelPendingRuntimeRetry();
        // The lane is up or gone: either way the ambient notices about the failed start are no longer true.
        adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.RECONNECTING);
        adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR);
    }

    private void cancelPendingRuntimeRetry() {
        ProtocolTaskHandle scheduled = pendingRuntimeRetry;
        pendingRuntimeRetry = null;
        if (scheduled != null && !scheduled.isDone()) {
            scheduled.cancel("LLM runtime retry replaced or stopped");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void stopRuntime() {
        lifecycleGeneration.incrementAndGet();
        ProtocolTaskHandle scheduled = delayedAutoLoad;
        delayedAutoLoad = null;
        if (scheduled != null && !scheduled.isDone()) {
            scheduled.cancel("LLM module stopped");
        }
        // A stopped module keeps no retry budget: the next start begins from the first attempt.
        clearRuntimeRetry();
        adapter.publishLoadingActivity(false);
        synchronized (lifecycleLock) {
            if (modelService != null) {
                modelService.stop();
            }
            adapter.setLlmService(null);
            LLMService stoppingService = llmService;
            llmService = null;
            if (serviceRegistry != null && stoppingService != null) {
                serviceRegistry.unregister(LLMService.class, stoppingService);
            }
            if (stoppingService != null) {
                stoppingService.shutdown();
            }
            OpenAiCompatibleInferenceClient stoppingRemote = remoteClient;
            remoteClient = null;
            if (stoppingRemote != null) {
                stoppingRemote.shutdown();
            }
            engineProvider.stop();
            ragStorageService.shutdown();
            if (moduleService != null) {
                moduleService.markStopped();
            }
            if (config.isLlmEnabled()) {
                markGenerationCapabilityFailed("LLM model is not loaded");
                markStorageCapabilitiesReady();
            } else {
                disableCapabilities();
            }
        }
        if (lifecycleActive) {
            lifecycleActive = false;
            publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleStatusGeneration));
        }
    }

    /**
     * The embedding lane settled, on the model-load lane: the card worked, the card failed and the CPU carried it,
     * or neither could embed.
     *
     * <p>A lane that cannot embed is unbound here rather than left in place: the RAG storage then reports "no
     * vector lane" instead of handing every write to a service that already knows it cannot answer.</p>
     *
     * <p>Only "cannot embed at all" is a product state. Running on the CPU instead of the GPU is a performance
     * detail the player cannot act on, so it is logged and left to module status and diagnostics; the ERROR notice
     * is retracted when a later load succeeds.</p>
     */
    private void onEmbeddingLoadSettled(EmbeddingLoadReport report) {
        switch (report.outcome()) {
            case READY -> adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR);
            case DEGRADED -> env.warn("[LLM] Embedding lane runs on the CPU; retrieval continues on the reduced backend");
            case UNAVAILABLE -> {
                ragStorageService.bindEmbeddingService(engineProvider.embeddingService());
                adapter.publishRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR, RUNTIME_ERROR_TTL_MILLIS);
            }
        }
    }

    /** The status a module reports when it is not — or no longer — running. */
    private void publishStoppedStatus() {
        publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleStatusGeneration));
    }

    private void publishModuleStatus(ModuleStatus status) {
        if (status != null) {
            adapter.publishModuleStatus(status);
        }
    }

    void publishInferenceStatus(com.rheinmetal.tianshu.function.weaving.contract.LlmStatusPayload status) {
        if (status == null) {
            return;
        }
        adapter.publishInferenceStatus(status);
        if (!"COMPLEX".equals(status.taskType()) || status.taskId().isBlank()) {
            return;
        }
        if (com.rheinmetal.tianshu.function.weaving.contract.LlmStatusPayload.STARTED.equals(status.eventType())) {
            adapter.publishTaskActivity(status.taskId(), true);
            return;
        }
        if (com.rheinmetal.tianshu.function.weaving.contract.LlmStatusPayload.COMPLETED.equals(status.eventType())
                || com.rheinmetal.tianshu.function.weaving.contract.LlmStatusPayload.CANCELLED.equals(status.eventType())
                || com.rheinmetal.tianshu.function.weaving.contract.LlmStatusPayload.FAILED.equals(status.eventType())) {
            adapter.publishTaskActivity(status.taskId(), false);
        }
    }

    private LLMRuntimeSnapshotPayload runtimeSnapshotWhenGenerationUnavailable() {
        String failure = moduleService == null ? "" : moduleService.snapshot().failureMessage();
        return new LLMRuntimeSnapshotPayload(
                false,
                false,
                ragStorageService.embeddingAvailable(),
                -1,
                false,
                false,
                false,
                false,
                false,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                0,
                0,
                0,
                "",
                "",
                config.getLlmEmbeddingModelName(),
                ragCacheLayout.cacheNamespace(),
                failure,
                System.currentTimeMillis()
        );
    }

}


