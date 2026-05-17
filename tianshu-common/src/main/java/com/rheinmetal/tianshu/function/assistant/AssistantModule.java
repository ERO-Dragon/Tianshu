package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextBudget;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextCollector;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextOrchestrator;
import com.rheinmetal.tianshu.function.assistant.context.AssistantGenerationOptionsFactory;
import com.rheinmetal.tianshu.function.assistant.context.AssistantMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.assistant.fact.BasicWorldStateRuntimeFactProvider;
import com.rheinmetal.tianshu.function.assistant.fact.InventoryRuntimeFactProvider;
import com.rheinmetal.tianshu.function.assistant.fact.RuntimeFactCollector;
import com.rheinmetal.tianshu.function.assistant.fact.RuntimeFactPool;
import com.rheinmetal.tianshu.function.assistant.input.AssistantInputNormalizer;
import com.rheinmetal.tianshu.function.assistant.memory.AssistantCompressionTaskDispatcher;
import com.rheinmetal.tianshu.function.assistant.memory.AssistantMemorySystem;
import com.rheinmetal.tianshu.function.assistant.memory.MemoryConsolidationPlanner;
import com.rheinmetal.tianshu.function.assistant.output.AssistantOutputProcessor;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptPlanner;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptRenderer;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptResourceRepository;
import com.rheinmetal.tianshu.function.assistant.rag.AssistantRagPathClient;
import com.rheinmetal.tianshu.function.assistant.rag.AssistantRagPathResolution;
import com.rheinmetal.tianshu.function.assistant.rag.DynamicRagCandidateBuilder;
import com.rheinmetal.tianshu.function.assistant.rag.DynamicRagUpdatePolicy;
import com.rheinmetal.tianshu.function.assistant.rag.RuntimeFactTextRenderer;
import com.rheinmetal.tianshu.function.assistant.rag.RuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.assistant.runtime.AssistantRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.assistant.runtime.AssistantRuntimeMaintenancePolicy;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScopeProvider;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantWorldIdentityProvider;
import com.rheinmetal.tianshu.function.assistant.scope.DefaultAssistantScopeProvider;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantJsonStore;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantStorageLayout;
import com.rheinmetal.tianshu.function.ia.IaModuleService;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class AssistantModule implements TianshuManagedModule {
    public static final String MODULE_ID = "module.assistant";

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime runtime;
    private final AssistantWorldIdentityProvider worldIdentityProvider;
    private final WorldStateProvider worldStateProvider;
    private final RuntimeFactTextResolver runtimeFactTextResolver;
    private final AssistantProtocolAdapter adapter;
    private AssistantDialogueGateway dialogueGateway;
    private AssistantParticipantRegistrar participantRegistrar;
    private AssistantConversationService conversationService;
    private AssistantLlmClient llmClient;
    private AssistantRagPathClient ragPathClient;
    private AssistantStorageLayout storageLayout;

    public AssistantModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime) {
        this(env, config, runtime, null, null, null);
    }

    public AssistantModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime, AssistantWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider) {
        this(env, config, runtime, worldIdentityProvider, worldStateProvider, null);
    }

    public AssistantModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime, AssistantWorldIdentityProvider worldIdentityProvider, WorldStateProvider worldStateProvider, RuntimeFactTextResolver runtimeFactTextResolver) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.worldStateProvider = worldStateProvider;
        this.runtimeFactTextResolver = runtimeFactTextResolver;
        this.adapter = new AssistantProtocolAdapter(runtime);
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        llmClient = new AssistantLlmClient(adapter);
        ragPathClient = new AssistantRagPathClient(adapter);
        conversationService = createConversationService(llmClient);
        AssistantAccessController accessController = new AssistantAccessController();
        dialogueGateway = new AssistantDialogueGateway(conversationService, adapter, accessController, new AssistantLlmRequestFactory(), llmClient);
        adapter.registerDialogueDeliveryCapability(dialogueGateway::handleDelivery);
        adapter.registerLlmTaskStreamChunkRoute(dialogueGateway::handleLlmStreamChunk);
        adapter.registerLlmTaskResultRoute(dialogueGateway::handleLlmResult);
        adapter.registerLlmRagPathResultRoute(this::handleRagPathResult);
        context.services().find(IaModuleService.class).ifPresent(service -> participantRegistrar = new AssistantParticipantRegistrar(service));
        if (participantRegistrar != null) {
            participantRegistrar.register();
        }
        context.services().register(AssistantConversationService.class, conversationService);
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

    private AssistantConversationService createConversationService(AssistantLlmClient llmClient) {
        storageLayout = new AssistantStorageLayout(config);
        AssistantJsonStore jsonStore = new AssistantJsonStore(env);
        AssistantMemoryWindowPolicy memoryWindowPolicy = AssistantMemoryWindowPolicy.fromConfig(config);
        AssistantMemorySystem memorySystem = new AssistantMemorySystem(storageLayout, jsonStore, memoryWindowPolicy);
        RuntimeFactPool runtimeFactPool = new RuntimeFactPool();
        RuntimeFactCollector runtimeFactCollector = new RuntimeFactCollector(runtimeFactPool);
        if (worldStateProvider != null) {
            runtimeFactCollector.registerProvider(new BasicWorldStateRuntimeFactProvider(worldStateProvider));
            runtimeFactCollector.registerProvider(new InventoryRuntimeFactProvider(worldStateProvider));
        }
        AssistantScopeProvider scopeProvider = worldIdentityProvider == null ? new DefaultAssistantScopeProvider(env) : new DefaultAssistantScopeProvider(worldIdentityProvider);
        DynamicRagCandidateBuilder ragCandidateBuilder = new DynamicRagCandidateBuilder(runtimeFactPool, DynamicRagUpdatePolicy.DEFAULT, new RuntimeFactTextRenderer(runtimeFactTextResolver));
        AssistantContextCollector contextCollector = new AssistantContextCollector(memorySystem, ragCandidateBuilder);
        AssistantPromptResourceRepository promptResourceRepository = new AssistantPromptResourceRepository(storageLayout, jsonStore);
        AssistantCompressionTaskDispatcher compressionTaskDispatcher = new AssistantCompressionTaskDispatcher(memorySystem, llmClient, scopeProvider, promptResourceRepository);
        AssistantRuntimeMaintenanceCoordinator maintenanceCoordinator = new AssistantRuntimeMaintenanceCoordinator(
                runtimeFactCollector,
                memorySystem,
                compressionTaskDispatcher,
                new MemoryConsolidationPlanner(),
                AssistantRuntimeMaintenancePolicy.DEFAULT
        );
        AssistantContextOrchestrator contextOrchestrator = new AssistantContextOrchestrator(
                AssistantContextBudget.fromPolicy(memoryWindowPolicy),
                new AssistantPromptPlanner(promptResourceRepository),
                new AssistantPromptRenderer()
        );
        AssistantOutputProcessor outputProcessor = new AssistantOutputProcessor(memorySystem);
        return new AssistantConversationService(
                scopeProvider,
                contextCollector,
                contextOrchestrator,
                maintenanceCoordinator,
                new AssistantInputNormalizer(),
                outputProcessor,
                new AssistantGenerationOptionsFactory(memoryWindowPolicy)
        );
    }
}
