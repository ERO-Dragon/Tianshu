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
import com.rheinmetal.tianshu.function.auxilium.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptPlanner;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptRenderer;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagCandidateBuilder;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagUpdatePolicy;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextRenderer;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.runtime.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.runtime.AXRuntimeMaintenancePolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.DefaultAXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.ia.IaModuleService;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
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
    private final AXOutputSettings outputSettings;
    private final AXChatOutputSink chatOutputSink;
    private final AXProtocolAdapter adapter;
    private AXParticipantRegistrar participantRegistrar;
    private AXLlmClient llmClient;
    private AXStorageLayout storageLayout;
    private AXDialogueGateway dialogueGateway;

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
        this(env, config, runtime, worldIdentityProvider, worldStateProvider, runtimeFactTextResolver, promptLanguageProvider, AXOutputSettings.DEFAULT, AXChatOutputSink.NOOP);
    }

    public AXModule(
            IGameEnvironment env,
            ITianshuConfig config,
            ProtocolRuntime runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            WorldStateProvider worldStateProvider,
            RuntimeFactTextResolver runtimeFactTextResolver,
            AXPromptLanguageProvider promptLanguageProvider,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.worldStateProvider = worldStateProvider;
        this.runtimeFactTextResolver = runtimeFactTextResolver;
        this.promptLanguageProvider = promptLanguageProvider == null ? AXPromptLanguageProvider.fixed(com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage.EN_US) : promptLanguageProvider;
        this.outputSettings = outputSettings == null ? AXOutputSettings.DEFAULT : outputSettings;
        this.chatOutputSink = chatOutputSink == null ? AXChatOutputSink.NOOP : chatOutputSink;
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
        AXJsonStore jsonStore = new AXJsonStore(env);
        AXMemoryWindowPolicy memoryPolicy = AXMemoryWindowPolicy.fromConfig(config);
        AXScopeProvider scopeProvider = worldIdentityProvider == null
                ? new DefaultAXScopeProvider(env)
                : new DefaultAXScopeProvider(worldIdentityProvider);
        AXMemorySystem memorySystem = new AXMemorySystem(storageLayout, jsonStore, memoryPolicy);
        RuntimeFactPool factPool = new RuntimeFactPool();
        RuntimeFactCollector factCollector = new RuntimeFactCollector(factPool);
        registerRuntimeFactProviders(factCollector);
        RuntimeFactTextRenderer factTextRenderer = new RuntimeFactTextRenderer(runtimeFactTextResolver);
        DynamicRagCandidateBuilder ragCandidateBuilder = new DynamicRagCandidateBuilder(
                factPool,
                DynamicRagUpdatePolicy.DEFAULT,
                factTextRenderer,
                promptLanguageProvider
        );
        AXRuntimeMaintenanceCoordinator maintenanceCoordinator = new AXRuntimeMaintenanceCoordinator(
                factCollector,
                memorySystem,
                null,
                AXRuntimeMaintenancePolicy.DEFAULT
        );
        AXContextCollector contextCollector = new AXContextCollector(memorySystem, ragCandidateBuilder);
        AXPromptResourceRepository promptRepository = new AXPromptResourceRepository(storageLayout, jsonStore);
        AXPromptPlanner promptPlanner = new AXPromptPlanner(promptRepository, promptLanguageProvider);
        AXContextBudget contextBudget = AXContextBudget.fromPolicy(memoryPolicy);
        AXLlmPromptRequestBuilder llmRequestBuilder = new AXLlmPromptRequestBuilder(
                promptPlanner,
                new AXPromptRenderer(),
                contextBudget
        );
        AXSessionController sessionController = new AXSessionController(adapter);
        AXOutputProcessor outputProcessor = new AXOutputProcessor(adapter, outputSettings, chatOutputSink);
        AXTurnOrchestrator turnOrchestrator = new AXTurnOrchestrator(
                scopeProvider,
                new AXInputNormalizer(),
                maintenanceCoordinator,
                contextCollector,
                llmRequestBuilder,
                llmClient,
                sessionController,
                memorySystem,
                outputProcessor
        );
        dialogueGateway = new AXDialogueGateway(new AXAccessController(), turnOrchestrator);
        adapter.registerDialogueDeliveryCapability(dialogueGateway::handleDelivery);
        adapter.registerLlmPromptStreamChunkRoute(this::handleLlmStreamChunk);
        adapter.registerLlmPromptResultRoute(this::handleLlmResult);
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
        dialogueGateway = null;
    }

    private void registerRuntimeFactProviders(RuntimeFactCollector factCollector) {
        if (factCollector == null || worldStateProvider == null) {
            return;
        }
        factCollector.registerProvider(new BasicWorldStateRuntimeFactProvider(worldStateProvider));
        factCollector.registerProvider(new InventoryRuntimeFactProvider(worldStateProvider));
        factCollector.registerProvider(new ActiveEffectsRuntimeFactProvider(worldStateProvider));
        factCollector.registerProvider(new RecentChatRuntimeFactProvider(worldStateProvider));
    }

    private void handleLlmStreamChunk(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMPromptStreamChunkPayload payload)) {
            if (context != null) {
                context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX LLM stream payload is invalid", null);
            }
            return;
        }
        if (llmClient != null) {
            llmClient.handleStreamChunk(envelope.parentId(), payload);
        }
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void handleLlmResult(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMPromptResultPayload payload)) {
            if (context != null) {
                context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX LLM result payload is invalid", null);
            }
            return;
        }
        if (llmClient != null) {
            llmClient.handleResult(envelope.parentId(), payload);
        }
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }
}
