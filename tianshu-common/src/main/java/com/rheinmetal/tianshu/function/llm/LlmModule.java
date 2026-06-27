package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.DefaultWorldScopeProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;
import com.rheinmetal.tianshu.function.llm.rag.LlmRagCacheLayout;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.llm.service.JavaLlamaInferenceClient;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.List;

public final class LlmModule implements TianshuManagedModule {
    private static final List<RuntimeCapability> PROVIDED_CAPABILITIES = List.of(
            LlmRuntimeCapabilities.LLM_REQUEST,
            LlmRuntimeCapabilities.LLM_CACHE_MANAGE,
            LlmRuntimeCapabilities.LLM_PRIMITIVE_QUERY
    );

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime runtime;
    private final WorldScopeProvider scopeProvider;
    private final LlmRagCacheLayout ragCacheLayout;
    private final LlmEngineProvider engineProvider;
    private final LlmProtocolAdapter adapter;
    private final Object lifecycleLock = new Object();
    private LLMService llmService;
    private ModuleRuntimeContext runtimeContext;
    private LlmModuleService moduleService;
    private LlmModelService modelService;
    private boolean destroyed;

    public LlmModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime) {
        this(env, config, runtime, new DefaultWorldIdentityProvider(env));
    }

    public LlmModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime, WorldIdentityProvider worldIdentityProvider) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
        this.scopeProvider = new DefaultWorldScopeProvider(worldIdentityProvider == null ? new DefaultWorldIdentityProvider(env) : worldIdentityProvider);
        this.ragCacheLayout = new LlmRagCacheLayout(config, scopeProvider);
        this.adapter = new LlmProtocolAdapter(runtime, null, LlmTaskAdmissionController.fromConfig(config));
        this.engineProvider = new LlmEngineProvider(env, config, adapter::publishInferenceStatus);
    }

    @Override
    public String moduleId() {
        return "module.llm";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
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
        modelService = new LlmModelService(env, config, runtime.executors(), this::publishModuleStatus);
        adapter.registerLLMRequestCapability(this::handleLLMRequest);
        adapter.registerLLMCacheManageCapability(this::handleLLMCacheManage);
        adapter.registerLLMPrimitiveQueryCapability(this::handleLLMPrimitiveQuery);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        runtimeContext = context;
        markCapabilitiesInstalled(context);
        if (config.isLlmEnabled()) {
            markCapabilitiesFailed("LLM model is not loaded");
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

        publishModuleStatus(ModuleStatuses.startingKeyed(moduleId(), "tianshu.presence.module.llm.starting", "LLM 核心加载中"));
        engineProvider.startAsync(() -> {
            synchronized (lifecycleLock) {
                if (destroyed || moduleService == null || moduleService.snapshot().state() != LlmRuntimeState.STARTING) {
                    return;
                }
                JavaLlamaServer aiService = engineProvider.currentAiService();
                if (aiService == null) {
                    markCapabilitiesFailed("LLM service failed to start");
                    moduleService.markFailed("LLM service failed to start");
                    publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.llm.failed", "LLM 核心启动失败"));
                    return;
                }
                llmService = LLMService.builder()
                        .env(env)
                        .config(config)
                        .inferenceClient(new JavaLlamaInferenceClient(aiService))
                        .performanceProvider(moduleService)
                        .usePersistentCache(true)
                        .cacheDirectory(ragCacheLayout.currentWorldCacheDirectory())
                        .globalCacheDirectory(ragCacheLayout.globalCacheDirectory())
                        .cacheNamespace(ragCacheLayout.cacheNamespace())
                        .build();
                adapter.setLlmService(llmService);
                markCapabilitiesReady();
                moduleService.markReady();
            }
            publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.presence.module.llm.ready", "LLM 核心已就绪"));
        }, () -> {
            markCapabilitiesFailed("LLM service failed to start");
            if (moduleService != null) {
                moduleService.markFailed("LLM service failed to start");
            }
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.llm.failed", "LLM 核心启动失败"));
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
        synchronized (lifecycleLock) {
            adapter.setLlmService(null);
            LLMService stoppingService = llmService;
            llmService = null;
            if (stoppingService != null) {
                stoppingService.shutdown();
            }
            engineProvider.stop();
            if (moduleService != null) {
                moduleService.markStopped();
            }
            if (config.isLlmEnabled()) {
                markCapabilitiesFailed("LLM model is not loaded");
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

}


