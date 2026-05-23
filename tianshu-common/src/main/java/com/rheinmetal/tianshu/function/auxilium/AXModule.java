package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.context.AXGenerationOptionsFactory;
import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.fact.ActiveEffectsRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.BasicWorldStateRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.InventoryRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.RecentChatRuntimeFactProvider;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFactCollector;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFactPool;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.memory.AXCompressionTaskDispatcher;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.memory.MemoryConsolidationPlanner;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptPlanner;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptRenderer;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.rag.AXRagPathClient;
import com.rheinmetal.tianshu.function.auxilium.rag.AXRagPathResolution;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagCandidateBuilder;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagUpdatePolicy;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextRenderer;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.auxilium.runtime.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.runtime.AXRuntimeMaintenancePolicy;
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
    private AXDialogueGateway dialogueGateway;
    private AXParticipantRegistrar participantRegistrar;
    private AXConversationService conversationService;
    private AXLlmClient llmClient;
    private AXRagPathClient ragPathClient;
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
        ragPathClient = new AXRagPathClient(adapter);
        conversationService = createConversationService(llmClient);
        AXAccessController accessController = new AXAccessController();
        dialogueGateway = new AXDialogueGateway(conversationService, adapter, accessController, new AXLlmRequestFactory(), llmClient);
        adapter.registerDialogueDeliveryCapability(dialogueGateway::handleDelivery);
        adapter.registerLlmTaskStreamChunkRoute(dialogueGateway::handleLlmStreamChunk);
        adapter.registerLlmTaskResultRoute(dialogueGateway::handleLlmResult);
        adapter.registerLlmRagPathResultRoute(this::handleRagPathResult);
        context.services().find(IaModuleService.class).ifPresent(service -> participantRegistrar = new AXParticipantRegistrar(service));
        if (participantRegistrar != null) {
            participantRegistrar.register();
        }
        context.services().register(AXConversationService.class, conversationService);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (llmClient != null) {
            llmClient.sweepExpired();
        }
        if (ragPathClient != null) {
            ragPathClient.requestCurrent();
        }
    }

    @Override
    public void stop() {
        if (dialogueGateway != null) {
            dialogueGateway.cancelActiveGeneration();
        }
        if (llmClient != null) {
            llmClient.clear();
        }
        if (ragPathClient != null) {
            ragPathClient.clear();
        }
        if (participantRegistrar != null) {
            participantRegistrar.unregister();
        }
    }

    @Override
    public void destroy() {
        stop();
        dialogueGateway = null;
        participantRegistrar = null;
        conversationService = null;
        llmClient = null;
        ragPathClient = null;
        storageLayout = null;
    }

    private void handleRagPathResult(com.rheinmetal.tianshu.protocol.TianshuEnvelope envelope, com.rheinmetal.tianshu.protocol.runtime.ProtocolContext context) {
        if (ragPathClient == null || storageLayout == null || envelope == null || !(envelope.payload() instanceof com.rheinmetal.tianshu.protocol.payload.LlmRagPathResultPayload payload)) {
            return;
        }
        if (ragPathClient.handleResult(envelope.envelopeId(), payload)) {
            ragPathClient.latest().ifPresent(storageLayout::updateRagPathResolution);
        }
    }

    private AXConversationService createConversationService(AXLlmClient llmClient) {
        storageLayout = new AXStorageLayout(config);
        AXJsonStore jsonStore = new AXJsonStore(env);
        AXMemoryWindowPolicy memoryWindowPolicy = AXMemoryWindowPolicy.fromConfig(config);
        AXMemorySystem memorySystem = new AXMemorySystem(storageLayout, jsonStore, memoryWindowPolicy);
        RuntimeFactPool runtimeFactPool = new RuntimeFactPool();
        RuntimeFactCollector runtimeFactCollector = new RuntimeFactCollector(runtimeFactPool);
        if (worldStateProvider != null) {
            runtimeFactCollector.registerProvider(new BasicWorldStateRuntimeFactProvider(worldStateProvider));
            runtimeFactCollector.registerProvider(new ActiveEffectsRuntimeFactProvider(worldStateProvider));
            runtimeFactCollector.registerProvider(new InventoryRuntimeFactProvider(worldStateProvider));
            runtimeFactCollector.registerProvider(new RecentChatRuntimeFactProvider(worldStateProvider));
        }
        AXScopeProvider scopeProvider = worldIdentityProvider == null ? new DefaultAXScopeProvider(env) : new DefaultAXScopeProvider(worldIdentityProvider);
        RuntimeFactTextRenderer runtimeFactTextRenderer = new RuntimeFactTextRenderer(runtimeFactTextResolver);
        DynamicRagCandidateBuilder ragCandidateBuilder = new DynamicRagCandidateBuilder(runtimeFactPool, DynamicRagUpdatePolicy.DEFAULT, runtimeFactTextRenderer, promptLanguageProvider);
        AXContextCollector contextCollector = new AXContextCollector(memorySystem, ragCandidateBuilder);
        AXPromptResourceRepository promptResourceRepository = new AXPromptResourceRepository(storageLayout, jsonStore);
        AXCompressionTaskDispatcher compressionTaskDispatcher = new AXCompressionTaskDispatcher(memorySystem, llmClient, scopeProvider, promptResourceRepository, promptLanguageProvider);
        AXRuntimeMaintenanceCoordinator maintenanceCoordinator = new AXRuntimeMaintenanceCoordinator(
                runtimeFactCollector,
                memorySystem,
                compressionTaskDispatcher,
                new MemoryConsolidationPlanner(),
                AXRuntimeMaintenancePolicy.DEFAULT
        );
        AXContextOrchestrator contextOrchestrator = new AXContextOrchestrator(
                AXContextBudget.fromPolicy(memoryWindowPolicy),
                new AXPromptPlanner(promptResourceRepository, promptLanguageProvider),
                new AXPromptRenderer()
        );
        AXOutputProcessor outputProcessor = new AXOutputProcessor(memorySystem);
        return new AXConversationService(
                scopeProvider,
                contextCollector,
                contextOrchestrator,
                maintenanceCoordinator,
                new AXInputNormalizer(),
                outputProcessor,
                new AXGenerationOptionsFactory(memoryWindowPolicy)
        );
    }
}
