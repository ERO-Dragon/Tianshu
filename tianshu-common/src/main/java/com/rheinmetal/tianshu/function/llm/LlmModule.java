package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.DefaultWorldScopeProvider;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.List;

public final class LlmModule implements TianshuManagedModule {
    private static final List<RuntimeCapability> PROVIDED_CAPABILITIES = List.of(
            LlmRuntimeCapabilities.LLM_REQUEST,
            LlmRuntimeCapabilities.LLM_CACHE_MANAGE
    );

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime runtime;
    private final WorldScopeProvider scopeProvider;
    private final LlmEngineProvider engineProvider;
    private final LlmProtocolAdapter adapter;
    private LLMService llmService;
    private ModuleRuntimeContext runtimeContext;
    private LlmModuleService moduleService;
    private LlmModelService modelService;

    public LlmModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
        this.scopeProvider = new DefaultWorldScopeProvider(new DefaultWorldIdentityProvider(env));
        this.engineProvider = new LlmEngineProvider(env, config);
        this.adapter = new LlmProtocolAdapter(runtime, null);
    }

    @Override
    public String moduleId() {
        return "module.llm";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        moduleService = new LlmModuleService(config);
        modelService = new LlmModelService(env, config, runtime.executors());
        context.services().register(LlmModuleService.class, moduleService);
        context.services().register(LlmModelService.class, modelService);
        adapter.registerLLMRequestCapability(this::handleLLMRequest);
        adapter.registerLLMCacheManageCapability(this::handleLLMCacheManage);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        runtimeContext = context;
        markCapabilitiesInstalled(context);

        if (!engineProvider.isAiServiceAvailable()) {
            markCapabilitiesFailed("LLM model not configured");
            return;
        }

        llmService = LLMService.builder()
                .env(env)
                .aiService(engineProvider.getAiService())
                .usePersistentCache(true)
                .cacheDirectory(config.getLlmBasePath().resolve("cache"))
                .build();

        adapter.setLlmService(llmService);

        if (runtimeContext != null) {
            runtimeContext.services().register(LLMService.class, llmService);
        }

        engineProvider.startAsync(() -> {
            markCapabilitiesReady();
            if (moduleService != null) {
                moduleService.markReady();
            }
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f中枢核心已就绪"));
        }, () -> {
            markCapabilitiesFailed("LLM service failed to start");
            if (moduleService != null) {
                moduleService.markFailed("LLM service failed to start");
            }
        });
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (moduleService != null) {
            moduleService.load();
        }
    }

    @Override
    public void stop() {
        if (llmService != null) {
            llmService.shutdown();
        }
        if (moduleService != null) {
            moduleService.unload();
        }
    }

    @Override
    public void destroy() {
        stop();
        engineProvider.stop();
        if (runtimeContext != null) {
            PROVIDED_CAPABILITIES.forEach(runtimeContext.runtimeState().capabilities()::remove);
        }
        runtimeContext = null;
    }

    private void handleLLMRequest(TianshuEnvelope envelope, ProtocolContext context) {
        try {
            adapter.handleLLMRequest(envelope);
        } finally {
            context.complete(envelope.envelopeId());
        }
    }

    private void handleLLMCacheManage(TianshuEnvelope envelope, ProtocolContext context) {
        try {
            adapter.handleLLMCacheManage(envelope);
        } finally {
            context.complete(envelope.envelopeId());
        }
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
}
