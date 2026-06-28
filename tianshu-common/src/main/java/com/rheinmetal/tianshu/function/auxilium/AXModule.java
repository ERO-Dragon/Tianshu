package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.auxilium.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextClient;
import com.rheinmetal.tianshu.function.auxilium.context.orchestration.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryMaintenanceService;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryRetriever;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryTaskPromptRepository;
import com.rheinmetal.tianshu.function.auxilium.memory.AXPresenceChatMessageMapper;
import com.rheinmetal.tianshu.function.auxilium.memory.AXPresenceWorldEventMapper;
import com.rheinmetal.tianshu.function.auxilium.runtime.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.DefaultAXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.ia.IaModuleService;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class AXModule implements TianshuManagedModule {
    public static final String MODULE_ID = "module.ax";

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolRuntime runtime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final AXPromptLanguageProvider promptLanguageProvider;
    private final AXAssistantSettings assistantSettings;
    private final AXOutputSettings outputSettings;
    private final AXChatOutputSink chatOutputSink;
    private final AXProtocolAdapter adapter;
    private AXParticipantRegistrar participantRegistrar;
    private AXLlmClient llmClient;
    private AXLlmPrimitiveClient retrievalPrimitiveClient;
    private AXLlmPrimitiveClient maintenancePrimitiveClient;
    private AXRuntimeContextClient runtimeContextClient;
    private AXRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private AXStorageLayout storageLayout;
    private AXScopeProvider scopeProvider;
    private AXMemorySystem memorySystem;
    private AXDialogueGateway dialogueGateway;
    private AXTurnStatusPublisher turnStatusPublisher;
    private final AXPresenceChatMessageMapper chatMessageMapper = new AXPresenceChatMessageMapper();
    private final AXPresenceWorldEventMapper worldEventMapper = new AXPresenceWorldEventMapper();

    public AXModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime) {
        this(env, config, runtime, null);
    }

    public AXModule(IGameEnvironment env, ITianshuConfig config, ProtocolRuntime runtime, AXWorldIdentityProvider worldIdentityProvider) {
        this(env, config, runtime, worldIdentityProvider, null, AXAssistantSettings.DEFAULT, AXOutputSettings.DEFAULT, AXChatOutputSink.NOOP);
    }

    public AXModule(
            IGameEnvironment env,
            ITianshuConfig config,
            ProtocolRuntime runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings assistantSettings,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this.env = env;
        this.config = config;
        this.runtime = runtime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.promptLanguageProvider = promptLanguageProvider == null ? AXPromptLanguageProvider.fixed(com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage.EN_US) : promptLanguageProvider;
        this.assistantSettings = assistantSettings == null ? AXAssistantSettings.DEFAULT : assistantSettings;
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
        turnStatusPublisher = new AXTurnStatusPublisher(adapter);
        retrievalPrimitiveClient = new AXLlmPrimitiveClient(adapter, 1_000L);
        maintenancePrimitiveClient = new AXLlmPrimitiveClient(adapter, 30_000L);
        storageLayout = new AXStorageLayout(config);
        AXJsonStore jsonStore = new AXJsonStore(env);
        AXMemoryWindowPolicy memoryPolicy = AXMemoryWindowPolicy.fromConfig(config);
        scopeProvider = worldIdentityProvider == null
                ? new DefaultAXScopeProvider(env)
                : new DefaultAXScopeProvider(worldIdentityProvider);
        memorySystem = new AXMemorySystem(storageLayout, jsonStore, memoryPolicy);
        AXMemoryTaskPromptRepository memoryTaskPromptRepository = new AXMemoryTaskPromptRepository(storageLayout, promptLanguageProvider);
        AXMemoryMaintenanceService memoryMaintenanceService = new AXMemoryMaintenanceService(
                adapter,
                memorySystem,
                llmClient,
                maintenancePrimitiveClient,
                memoryTaskPromptRepository
        );
        maintenanceCoordinator = new AXRuntimeMaintenanceCoordinator(memoryMaintenanceService);
        AXMemoryRetriever memoryRetriever = new AXMemoryRetriever(memorySystem, retrievalPrimitiveClient);
        AXContextCollector contextCollector = new AXContextCollector(memorySystem);
        AXPromptResourceRepository promptRepository = new AXPromptResourceRepository(storageLayout, jsonStore);
        AXPromptOrchestrator promptOrchestrator = new AXPromptOrchestrator(promptRepository, promptLanguageProvider, null);
        AXContextBudget contextBudget = AXContextBudget.fromPolicy(memoryPolicy);
        AXLlmPromptRequestBuilder llmRequestBuilder = new AXLlmPromptRequestBuilder(
                promptOrchestrator,
                contextBudget
        );
        runtimeContextClient = new AXRuntimeContextClient(adapter);
        AXSessionController sessionController = new AXSessionController(adapter);
        AXOutputProcessor outputProcessor = new AXOutputProcessor(adapter, outputSettings, chatOutputSink);
        AXTurnOrchestrator turnOrchestrator = new AXTurnOrchestrator(
                scopeProvider,
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                maintenanceCoordinator,
                runtimeContextClient,
                contextCollector,
                llmRequestBuilder,
                contextBudget,
                llmClient,
                sessionController,
                memorySystem,
                outputProcessor,
                memoryRetriever,
                turnStatusPublisher
        );
        dialogueGateway = new AXDialogueGateway(new AXAccessController(), turnOrchestrator, turnStatusPublisher);
        adapter.registerDialogueInputCapability(dialogueGateway::handleDelivery);
        adapter.subscribeAsrSpeechActivity(this::handleAsrSpeechActivity);
        adapter.subscribePresenceWorldEvents(this::handlePresenceWorldEvent);
        adapter.subscribePresenceChatMessages(this::handlePresenceChatMessage);
        context.services().find(IaModuleService.class).ifPresent(service -> participantRegistrar = new AXParticipantRegistrar(service, assistantSettings));
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
        if (retrievalPrimitiveClient != null) {
            retrievalPrimitiveClient.sweepExpired();
        }
        if (maintenancePrimitiveClient != null) {
            maintenancePrimitiveClient.sweepExpired();
        }
    }

    @Override
    public void stop() {
        if (maintenanceCoordinator != null) {
            maintenanceCoordinator.stop();
        }
        if (llmClient != null) {
            llmClient.clear();
        }
        if (retrievalPrimitiveClient != null) {
            retrievalPrimitiveClient.clear();
        }
        if (maintenancePrimitiveClient != null) {
            maintenancePrimitiveClient.clear();
        }
        if (runtimeContextClient != null) {
            runtimeContextClient.clear();
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
        retrievalPrimitiveClient = null;
        maintenancePrimitiveClient = null;
        runtimeContextClient = null;
        maintenanceCoordinator = null;
        storageLayout = null;
        scopeProvider = null;
        memorySystem = null;
        dialogueGateway = null;
        turnStatusPublisher = null;
    }

    private void handleAsrSpeechActivity(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrSpeechActivityPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX ASR speech activity payload is invalid", null);
            return;
        }
        if (payload.speaking()) {
            if (llmClient != null) {
                llmClient.cancelChatRequests(AXTurnCancellation.playerInterrupted("user started speaking"));
            }
            adapter.controlTts(new TtsControlPayload(
                    TtsControlPayload.Action.STOP_CURRENT,
                    "",
                    "user started speaking"
            ));
        }
        context.complete(envelope.envelopeId());
    }

    private void handlePresenceWorldEvent(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof PresenceWorldEventPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX presence world event payload is invalid", null);
            return;
        }
        AXMemorySystem currentMemory = memorySystem;
        AXScopeProvider currentScopeProvider = scopeProvider;
        if (currentMemory != null && currentScopeProvider != null) {
            AXScope scope = currentScopeProvider.currentScope();
            if (scope != null && scope.writable()) {
                currentMemory.appendAttachedWorldEvent(scope, worldEventMapper.map(scope, payload));
            }
        }
        context.complete(envelope.envelopeId());
    }

    private void handlePresenceChatMessage(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof PresenceChatMessagePayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX presence chat message payload is invalid", null);
            return;
        }
        AXMemorySystem currentMemory = memorySystem;
        AXScopeProvider currentScopeProvider = scopeProvider;
        if (currentMemory != null && currentScopeProvider != null) {
            AXScope scope = currentScopeProvider.currentScope();
            if (scope != null && scope.writable()) {
                currentMemory.appendRawTurn(scope, chatMessageMapper.map(scope, payload));
            }
        }
        context.complete(envelope.envelopeId());
    }

}
