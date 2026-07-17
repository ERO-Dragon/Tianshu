package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.function.llm.rag.LlmRagCacheLayout;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.llm.service.JavaLlamaInferenceClient;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.LlmEmbeddingServiceAdapter;
import com.rheinmetal.tianshu.function.llm.service.LlmRagStorageService;
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

    private final IGameEnvironment env;
    private final LlmConfiguration config;
    private final ModuleRuntimeAccess runtime;
    private final LlmRagCacheLayout ragCacheLayout;
    private final LlmRagStorageService ragStorageService;
    private final LlmExecutor llmExecutor;
    private final LlmEngineProvider engineProvider;
    private final LlmProtocolAdapter adapter;
    private final Object lifecycleLock = new Object();
    private LLMService llmService;
    private ModuleRuntimeContext runtimeContext;
    private LlmModuleService moduleService;
    private LlmModelService modelService;
    private ModuleServiceRegistry serviceRegistry;
    private boolean destroyed;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile ProtocolTaskHandle delayedAutoLoad;

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
        this.adapter = new LlmProtocolAdapter(runtime, null, LlmTaskAdmissionController.fromConfig(config));
        this.adapter.setRagStorageService(ragStorageService);
        this.adapter.setUnavailableRuntimeSnapshotSupplier(this::runtimeSnapshotWhenGenerationUnavailable);
        this.engineProvider = new LlmEngineProvider(env, config, adapter::publishInferenceStatus, llmExecutor.modelLoadExecutor());
    }

    @Override
    public String moduleId() {
        return "module.llm";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        serviceRegistry = context == null ? null : context.services();
        moduleService = new LlmModuleService(config);
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

        publishModuleStatus(ModuleStatuses.startingKeyed(moduleId(), "tianshu.presence.module.llm.starting", ""));
        engineProvider.startAsync(() -> {
            synchronized (lifecycleLock) {
                if (destroyed || generation != lifecycleGeneration.get() || moduleService == null
                        || moduleService.snapshot().state() != LlmRuntimeState.STARTING) {
                    return;
                }
                JavaLlamaServer aiService = engineProvider.currentAiService();
                if (aiService == null) {
                    markGenerationCapabilityFailed("LLM service failed to start");
                    markStorageCapabilitiesReady();
                    moduleService.markFailed("LLM service failed to start");
                    publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.llm.failed", ""));
                    return;
                }
                JavaLlamaInferenceClient inferenceClient = new JavaLlamaInferenceClient(aiService);
                ragStorageService.bindEmbeddingService(new LlmEmbeddingServiceAdapter(inferenceClient));
                llmService = LLMService.builder()
                        .env(env)
                        .config(config)
                        .inferenceClient(inferenceClient)
                        .performanceProvider(moduleService)
                        .usePersistentCache(true)
                        .cacheDirectory(ragCacheLayout.cacheDirectory())
                        .cacheNamespace(ragCacheLayout.cacheNamespace())
                        .embeddingConfigured(engineProvider.isEmbeddingConfigured())
                        .ragStorage(ragStorageService)
                        .ragSearchExecutor(llmExecutor.cpuExecutor())
                        .ragPersistenceScheduler(llmExecutor.ragPersistenceScheduler())
                        .build();
                if (serviceRegistry != null) {
                    serviceRegistry.register(LLMService.class, llmService);
                }
                adapter.setLlmService(llmService);
                markCapabilitiesReady();
                moduleService.markReady();
            }
            publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.presence.module.llm.ready", ""));
        }, () -> {
            if (generation != lifecycleGeneration.get()) {
                return;
            }
            markGenerationCapabilityFailed("LLM service failed to start");
            markStorageCapabilitiesReady();
            if (moduleService != null) {
                moduleService.markFailed("LLM service failed to start");
            }
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.llm.failed", ""));
            synchronized (lifecycleLock) {
                adapter.setLlmService(null);
                LLMService failedService = llmService;
                llmService = null;
                if (failedService != null) {
                    failedService.shutdown();
                }
                engineProvider.stop();
            }
        });
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
    }

    private void publishModuleStatus(ModuleStatus status) {
        if (status != null) {
            adapter.publishModuleStatus(status);
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


