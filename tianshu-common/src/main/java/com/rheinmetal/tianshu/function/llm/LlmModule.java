package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmContextTiers;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.function.llm.rag.LlmRagCacheLayout;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.llm.service.EmbeddingService;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.LlmRagStorageService;
import com.rheinmetal.tianshu.function.llm.service.OpenAiCompatibleInferenceClient;
import com.rheinmetal.tianshu.function.llm.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;

import java.time.Duration;
import java.util.List;
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
    private static final String STATUS_KEY_MISSING_API_KEY = "tianshu.presence.module.llm.missing_api_key";
    private static final String STATUS_KEY_REMOTE_INCOMPLETE = "tianshu.presence.module.llm.remote_incomplete";

    private final IGameEnvironment env;
    private final LlmConfiguration config;
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
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile ProtocolTaskHandle delayedAutoLoad;
    private volatile long lifecycleStatusGeneration;
    private volatile boolean lifecycleActive;

    public LlmModule(IGameEnvironment env, LlmConfiguration config, ModuleRuntimeAccess runtime) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
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
        this.engineProvider = new LlmEngineProvider(env, config, llmExecutor.modelLoadExecutor());
    }

    @Override
    public String moduleId() {
        return "module.llm";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        serviceRegistry = context == null ? null : context.services();
        moduleService = new LlmModuleService(config, usageLedger);
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
        publishModuleStatus(ModuleStatuses.lifecycleStarting(moduleId(), "tianshu.presence.module.llm.starting", lifecycleStatusGeneration));
        runtimeContext = context;
        markCapabilitiesInstalled(context);
        if (config.isLlmEnabled()) {
            markGenerationCapabilityFailed("LLM model is not loaded");
            markStorageCapabilitiesReady();
            if (moduleService != null) {
                moduleService.markStopped();
            }
        } else {
            disableCapabilities();
            if (moduleService != null) {
                moduleService.markStopped();
            }
            publishModuleStatus(ModuleStatuses.lifecycleFailed(moduleId(), "tianshu.presence.module.llm.failed", lifecycleStatusGeneration));
        }
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (!config.isLlmEnabled()) {
            return;
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

        publishModuleStatus(ModuleStatuses.startingKeyed(moduleId(), "tianshu.presence.module.llm.starting"));
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
        publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.presence.module.llm.ready"));
        publishModuleStatus(ModuleStatuses.lifecycleReady(moduleId(), "tianshu.presence.module.llm.ready", lifecycleStatusGeneration));
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
        int tier = LlmContextTiers.downgradeToLargestAvailable(config.getLlmContextTier(), windowTokens);
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
        publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), statusKey));
        publishModuleStatus(ModuleStatuses.lifecycleFailed(moduleId(), statusKey, lifecycleStatusGeneration));
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
            publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), "tianshu.presence.module.llm.stopped", lifecycleStatusGeneration));
        }
    }

    private void publishModuleStatus(ModuleStatus status) {
        if (status != null) {
            adapter.publishModuleStatus(status);
        }
    }

    void publishInferenceStatus(com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload status) {
        if (status == null) {
            return;
        }
        adapter.publishInferenceStatus(status);
        if (!"TASK".equals(status.taskType()) || status.taskId().isBlank()) {
            return;
        }
        if (com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload.STARTED.equals(status.eventType())) {
            adapter.publishTaskActivity(status.taskId(), true);
            return;
        }
        if (com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload.COMPLETED.equals(status.eventType())
                || com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload.CANCELLED.equals(status.eventType())
                || com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload.FAILED.equals(status.eventType())) {
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


