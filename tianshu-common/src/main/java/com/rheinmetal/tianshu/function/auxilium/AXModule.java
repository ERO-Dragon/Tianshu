package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXRuntimeLlmBudgetResolver;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.core.maintenance.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXAccessController;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXDialogueGateway;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXSessionController;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnStatusPublisher;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFactClient;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicKnowledgeFormatter;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXSharedKnowledgePlanner;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXPresenceChatMessageMapper;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXPresenceWorldEventMapper;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryMaintenanceService;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryTaskPromptRepository;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetriever;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnCheckpointStore;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.DefaultAXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.Map;

public final class AXModule implements TianshuManagedModule {
    public static final String MODULE_ID = "module.ax";

    private final IGameEnvironment env;
    private final AXStorageConfiguration storageConfiguration;
    private final ModuleRuntimeAccess runtime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final AXPromptLanguageProvider promptLanguageProvider;
    private final AXAssistantSettings assistantSettings;
    private final AXRuntimePolicy runtimePolicy;
    private final AXOutputSettings outputSettings;
    private final AXChatOutputSink chatOutputSink;
    private final AXProtocolAdapter adapter;
    private AXParticipantRegistrar participantRegistrar;
    private AXLlmClient llmClient;
    private AXLlmPrimitiveClient retrievalPrimitiveClient;
    private AXLlmPrimitiveClient maintenancePrimitiveClient;
    private AXLlmRagClient ragClient;
    private AXDynamicFactClient dynamicFactClient;
    private AXRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private AXStorageLayout storageLayout;
    private AXScopeProvider scopeProvider;
    private AXMemorySystem memorySystem;
    private AXRecentDialogueSystem recentDialogueSystem;
    private AXDialogueGateway dialogueGateway;
    private AXTurnStatusPublisher turnStatusPublisher;
    private final AXPresenceChatMessageMapper chatMessageMapper = new AXPresenceChatMessageMapper();
    private final AXPresenceWorldEventMapper worldEventMapper = new AXPresenceWorldEventMapper();

    public AXModule(IGameEnvironment env, AXStorageConfiguration storageConfiguration, ModuleRuntimeAccess runtime) {
        this(env, storageConfiguration, runtime, null);
    }

    public AXModule(IGameEnvironment env, AXStorageConfiguration storageConfiguration, ModuleRuntimeAccess runtime, AXWorldIdentityProvider worldIdentityProvider) {
        this(env, storageConfiguration, runtime, worldIdentityProvider, null, AXAssistantSettings.DEFAULT, AXOutputSettings.DEFAULT, AXChatOutputSink.NOOP);
    }

    public AXModule(
            IGameEnvironment env,
            AXStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings assistantSettings,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this(env, storageConfiguration, runtime, worldIdentityProvider, promptLanguageProvider, assistantSettings, AXRuntimePolicy.defaults(), outputSettings, chatOutputSink);
    }

    public AXModule(
            IGameEnvironment env,
            AXStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AXAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AXOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this.env = env;
        this.storageConfiguration = storageConfiguration;
        this.runtime = runtime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.promptLanguageProvider = promptLanguageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : promptLanguageProvider;
        this.assistantSettings = assistantSettings == null ? AXAssistantSettings.DEFAULT : assistantSettings;
        this.runtimePolicy = runtimePolicy == null ? AXRuntimePolicy.defaults() : runtimePolicy;
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
        adapter.registerDialogueInputCapability(this::handleDialogueDelivery);
        adapter.subscribeAsrSpeechActivity(this::handleAsrSpeechActivity);
        adapter.subscribePresenceWorldEvents(this::handlePresenceWorldEvent);
        adapter.subscribePresenceChatMessages(this::handlePresenceChatMessage);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        if (dialogueGateway != null) {
            return;
        }
        llmClient = new AXLlmClient(adapter);
        turnStatusPublisher = new AXTurnStatusPublisher(adapter);
        retrievalPrimitiveClient = new AXLlmPrimitiveClient(adapter, runtimePolicy.retrievalPrimitiveTimeoutMillis());
        maintenancePrimitiveClient = new AXLlmPrimitiveClient(adapter, runtimePolicy.maintenancePrimitiveTimeoutMillis());
        ragClient = new AXLlmRagClient(adapter, runtimePolicy.ragTimeoutMillis());
        storageLayout = new AXStorageLayout(storageConfiguration);
        AXJsonStore jsonStore = new AXJsonStore(env);
        AXMemoryWindowPolicy memoryPolicy = AXMemoryWindowPolicy.DEFAULT;
        scopeProvider = worldIdentityProvider == null
                ? new DefaultAXScopeProvider(env)
                : new DefaultAXScopeProvider(worldIdentityProvider);
        memorySystem = new AXMemorySystem(storageLayout, jsonStore, memoryPolicy);
        AXRawTurnCheckpointStore rawTurnCheckpointStore = new AXRawTurnCheckpointStore(storageLayout, jsonStore);
        recentDialogueSystem = new AXRecentDialogueSystem(memoryPolicy, retrievalPrimitiveClient::countMessageTokens, rawTurnCheckpointStore);
        AXMemoryTaskPromptRepository memoryTaskPromptRepository = new AXMemoryTaskPromptRepository(storageLayout, promptLanguageProvider);
        AXMemoryMaintenanceService memoryMaintenanceService = new AXMemoryMaintenanceService(
                adapter,
                memorySystem,
                recentDialogueSystem,
                llmClient,
                maintenancePrimitiveClient,
                ragClient,
                memoryTaskPromptRepository
        );
        maintenanceCoordinator = new AXRuntimeMaintenanceCoordinator(memoryMaintenanceService);
        AXMemoryRetriever memoryRetriever = new AXMemoryRetriever(memorySystem, ragClient);
        AXContextCollector contextCollector = new AXContextCollector(memorySystem, recentDialogueSystem);
        AXPromptResourceRepository promptRepository = new AXPromptResourceRepository(storageLayout, jsonStore);
        AXPromptOrchestrator promptOrchestrator = new AXPromptOrchestrator(
                promptRepository,
                promptLanguageProvider,
                new AXSharedKnowledgePlanner(ragClient),
                null,
                retrievalPrimitiveClient::countMessageTokens
        );
        AXContextBudget contextBudget = AXContextBudget.fromPolicy(memoryPolicy);
        AXLlmPromptRequestBuilder llmRequestBuilder = new AXLlmPromptRequestBuilder(promptOrchestrator, assistantSettings);
        AXRuntimeLlmBudgetResolver budgetResolver = new AXRuntimeLlmBudgetResolver(retrievalPrimitiveClient, memoryPolicy);
        dynamicFactClient = new AXDynamicFactClient(adapter, new AXDynamicKnowledgeFormatter(promptRepository, promptLanguageProvider), runtimePolicy.dynamicFactTimeoutMillis());
        AXSessionController sessionController = new AXSessionController(adapter);
        AXOutputProcessor outputProcessor = new AXOutputProcessor(adapter, outputSettings, chatOutputSink);
        AXTurnOrchestrator turnOrchestrator = new AXTurnOrchestrator(
                scopeProvider,
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                maintenanceCoordinator,
                dynamicFactClient,
                contextCollector,
                llmRequestBuilder,
                contextBudget,
                budgetResolver,
                llmClient,
                sessionController,
                memorySystem,
                recentDialogueSystem,
                outputProcessor,
                memoryRetriever,
                turnStatusPublisher
        );
        dialogueGateway = new AXDialogueGateway(new AXAccessController(), turnOrchestrator, turnStatusPublisher);
        participantRegistrar = new AXParticipantRegistrar(adapter, assistantSettings);
        participantRegistrar.register();
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
        if (ragClient != null) {
            ragClient.sweepExpired();
        }
    }

    @Override
    public void stop() {
        if (maintenanceCoordinator != null) {
            maintenanceCoordinator.stop();
        }
        if (recentDialogueSystem != null) {
            recentDialogueSystem.checkpointAll();
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
        if (ragClient != null) {
            ragClient.clear();
        }
        if (dynamicFactClient != null) {
            dynamicFactClient.clear();
        }
        if (participantRegistrar != null) {
            participantRegistrar.unregister();
        }
        dialogueGateway = null;
    }

    @Override
    public void destroy() {
        stop();
        participantRegistrar = null;
        llmClient = null;
        retrievalPrimitiveClient = null;
        maintenancePrimitiveClient = null;
        ragClient = null;
        dynamicFactClient = null;
        maintenanceCoordinator = null;
        storageLayout = null;
        scopeProvider = null;
        memorySystem = null;
        dialogueGateway = null;
        turnStatusPublisher = null;
    }

    private void handleDialogueDelivery(TianshuEnvelope envelope, ProtocolContext context) throws Exception {
        AXDialogueGateway currentGateway = dialogueGateway;
        if (currentGateway == null) {
            context.fail(envelope.envelopeId(), "AX_NOT_READY", "AX runtime is not prepared", null);
            return;
        }
        if (envelope.payload() instanceof DialogueDeliveryPayload payload) {
            env.diagnostics().publish(DiagnosticEvent.now(
                    MODULE_ID,
                    "DIALOGUE_DELIVERY",
                    DiagnosticSeverity.INFO,
                    DiagnosticPrivacy.RAW_CONTENT,
                    Map.of(
                            "sessionId", payload.sessionId(),
                            "requestId", payload.requestId(),
                            "repairedText", payload.repairedText(),
                            "normalizedText", payload.normalizedText()
                    )
            ));
        }
        currentGateway.handleDelivery(envelope, context);
    }

    private void handleAsrSpeechActivity(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrSpeechActivityPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX ASR speech activity payload is invalid", null);
            return;
        }
        if (payload.speaking() && assistantSettings.interruptOnPlayerSpeech()) {
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
                if (recentDialogueSystem != null) {
                    recentDialogueSystem.append(scope, chatMessageMapper.map(scope, payload));
                }
            }
        }
        context.complete(envelope.envelopeId());
    }

}
