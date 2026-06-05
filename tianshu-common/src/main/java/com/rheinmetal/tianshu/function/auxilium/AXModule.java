package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.auxilium.fact.ActiveEffectsRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.BasicWorldStateRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.InventoryRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.RecentChatRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFactCollector;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFactPool;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagCandidateBuilder;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagUpdatePolicy;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextRenderer;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.DefaultAXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.ia.IaModuleService;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class AXModule implements TianshuManagedModule {
    public static final String MODULE_ID = "module.ax";

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime runtime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final WorldStateProvider worldStateProvider;
    private final RuntimeFactTextResolver runtimeFactTextResolver;
    private final AXPromptLanguageProvider promptLanguageProvider;
    private final AXProtocolAdapter adapter;
    private AXParticipantRegistrar participantRegistrar;
    private AXLlmClient llmClient;
    private AXStorageLayout storageLayout;

    public AXModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime) {
        this(env, config, runtime, null, null, null);
    }

    public AXModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime, AXWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider) {
        this(env, config, runtime, worldIdentityProvider, worldStateProvider, null);
    }

    public AXModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime, AXWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider, RuntimeFactTextResolver runtimeFactTextResolver) {
        this(env, config, runtime, worldIdentityProvider, worldStateProvider, runtimeFactTextResolver, null);
    }

    public AXModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime, AXWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider, RuntimeFactTextResolver runtimeFactTextResolver, AXPromptLanguageProvider promptLanguageProvider) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.worldStateProvider = worldStateProvider;
        this.runtimeFactTextResolver = runtimeFactTextResolver;
        this.promptLanguageProvider = promptLanguageProvider == null ? AXPromptLanguageProvider.fixed(com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage.EN_US) : promptLanguageProvider;
        this.adapter = new AXProtocolAdapter(runtime);
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        llmClient = new AXLlmClient(adapter);
        storageLayout = new AXStorageLayout(config);
        context.services().find(IaModuleService.class).ifPresent(service -> participantRegistrar = new AXParticipantRegistrar(service));
        if (participantRegistrar != null) {
            participantRegistrar.register();
        }
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (llmClient != null) {
            llmClient.sweepExpired();
        }
    }

    @Override
    public void stop() {
        if (llmClient != null) {
            llmClient.clear();
        }
        if (participantRegistrar != null) {
            participantRegistrar.unregister();
        }
    }

    @Override
    public void destroy() {
        stop();
        participantRegistrar = null;
        llmClient = null;
        storageLayout = null;
    }
}
