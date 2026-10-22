package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
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
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDelivery;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateEventLedger;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegatePromptRepository;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateRegistry;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateService;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnOrchestrator;import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnStatusPublisher;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDispatcher;
import com.rheinmetal.tianshu.function.auxilium.event.AXGameEventService;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFactClient;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicKnowledgeFormatter;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXSharedKnowledgePlanner;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXDialogueContextInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXPresenceChatMessageMapper;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXPresenceWorldEventMapper;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryMaintenanceService;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryTaskPromptRepository;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetriever;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnCheckpointStore;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
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
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueContextInputPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

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
    private AXTurnOrchestrator turnOrchestrator;
    private AXDelegateService delegateService;
    private AXTurnStatusPublisher turnStatusPublisher;
    private AXGameEventService gameEventService;
    private ModuleServiceRegistry moduleServices;
    private long lifecycleStatusGeneration;
    private boolean lifecycleActive;
    private final AXPresenceChatMessageMapper chatMessageMapper = new AXPresenceChatMessageMapper();
    private final AXDialogueContextInputMapper contextInputMapper = new AXDialogueContextInputMapper();
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
        adapter.registerContextInputCapability(this::handleContextInput);
        adapter.subscribePresenceWorldEvents(this::handlePresenceWorldEvent);
        adapter.subscribePresenceChatMessages(this::handlePresenceChatMessage);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        if (dialogueGateway != null) {
            return;
        }
        lifecycleStatusGeneration++;
        lifecycleActive = true;
        adapter.publishModuleStatus(ModuleStatuses.lifecycleStarting(moduleId(), "tianshu.presence.module.ax.starting", lifecycleStatusGeneration));
        llmClient = new AXLlmClient(adapter);
        turnStatusPublisher = new AXTurnStatusPublisher(adapter);
        retrievalPrimitiveClient = new AXLlmPrimitiveClient(adapter, runtimePolicy.retrievalPrimitiveTimeoutMillis());
        maintenancePrimitiveClient = new AXLlmPrimitiveClient(adapter, runtimePolicy.maintenancePrimitiveTimeoutMillis());
        ragClient = new AXLlmRagClient(adapter, runtimePolicy.ragTimeoutMillis());
        storageLayout = new AXStorageLayout(storageConfiguration);
        AXJsonStore jsonStore = new AXJsonStore(env);
        // Construction-time fallback only. The real budget is resolved per turn
        // (AXTurnOrchestrator -> AXRuntimeLlmBudgetResolver.resolveContextBudget -> fromSnapshot ->
        // AXMemoryWindowPolicy.fromBudget) and overrides this value for everything a turn touches.
        // This one is what runs before the first resolution and whenever resolution fails, so it must
        // stay the contract floor rather than an optimistic guess.
        AXMemoryWindowPolicy constructionFallbackPolicy = AXMemoryWindowPolicy.DEFAULT;
        scopeProvider = worldIdentityProvider == null
                ? new DefaultAXScopeProvider(env)
                : new DefaultAXScopeProvider(worldIdentityProvider);
        // The memory system only reads shortTermChatBlockLimit out of the policy, and fromSnapshot
        // carries that field over unchanged, so a construction-time value is correct here by design:
        // it needs no per-turn refresh.
        memorySystem = new AXMemorySystem(storageLayout, jsonStore, constructionFallbackPolicy);
        AXRawTurnCheckpointStore rawTurnCheckpointStore = new AXRawTurnCheckpointStore(storageLayout, jsonStore);
        // The recent-dialogue window does depend on the real window size, so it starts on the fallback
        // and is re-pointed every resolved turn (AXTurnOrchestrator.applyWindowPolicy).
        recentDialogueSystem = new AXRecentDialogueSystem(constructionFallbackPolicy, retrievalPrimitiveClient::countMessageTokens, rawTurnCheckpointStore);
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
                null
        );
        // Fallback context budget for the very first turn (and for a failed budget lookup). The prompt
        // side of every resolved turn uses AXRuntimeLlmBudget.contextBudget() instead, which is derived
        // from the same real window as the memory window policy.
        AXContextBudget contextBudget = AXContextBudget.fromPolicy(constructionFallbackPolicy);
        AXLlmPromptRequestBuilder llmRequestBuilder = new AXLlmPromptRequestBuilder(promptOrchestrator, assistantSettings);
        AXRuntimeLlmBudgetResolver budgetResolver = new AXRuntimeLlmBudgetResolver(retrievalPrimitiveClient, constructionFallbackPolicy);
        dynamicFactClient = new AXDynamicFactClient(adapter, new AXDynamicKnowledgeFormatter(promptRepository, promptLanguageProvider), runtimePolicy.dynamicFactTimeoutMillis());
        AXSessionController sessionController = new AXSessionController(adapter);
        AXOutputProcessor outputProcessor = new AXOutputProcessor(adapter, outputSettings, chatOutputSink);
        delegateService = new AXDelegateService(
                llmClient::submitDetached,
                new AXDelegateRegistry(),
                new AXDelegatePromptRepository(promptLanguageProvider),
                null,
                new AXDelegateEventLedger(),
                this::appendHistoryEvent,
                adapter::submitDelegateRun,
                this::publishDelegateDelivery,
                env.logs(),
                System::currentTimeMillis
        );
        turnOrchestrator = new AXTurnOrchestrator(
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
                turnStatusPublisher,
                assistantSettings.allowInterruption(),
                context.diagnostics(),
                adapter::submitAgentTurn,
                delegateService
        );
        dialogueGateway = new AXDialogueGateway(new AXAccessController(), turnOrchestrator, turnStatusPublisher);
        participantRegistrar = new AXParticipantRegistrar(adapter, assistantSettings);
        participantRegistrar.register();
        publishGameEventService(context);
        if (moduleServices != null) {
            moduleServices.register(AXDelegateService.class, delegateService);
        }
        adapter.publishModuleStatus(ModuleStatuses.lifecycleReady(moduleId(), "tianshu.presence.module.ax.ready", lifecycleStatusGeneration));
    }

    /**
     * Exposes the delegation pipeline so later slices (voice, memory write-back) can reach it.
     *
     * <p>The delivery handle is reported through the log only: TTS is not wired in this slice, and this
     * layer never renders player wording — the resource key is the contract (design §6.2).</p>
     */
    private void publishDelegateDelivery(AXDelegateDelivery delivery) {
        if (delivery == null) {
            return;
        }
        env.logs().info("ax.delegate.speech_pending task=" + delivery.taskId()
                + (delivery.utteranceKey() == null ? "" : " key=" + delivery.utteranceKey())
                + (delivery.isAck() ? " ack=true" : " kind=" + delivery.kind().code()));
    }

    /**
     * Publishes the event pipeline as a module service so any module can submit events.
     *
     * <p>The service is registered under {@link AXGameEventService} for the whole session, so a managed
     * module reaches it through {@code context.services().find(AXGameEventService.class)} and a
     * platform layer through {@code coreManager.findService(AXGameEventService.class)}. It owns the
     * only decision path (tiers, blacklist, speech gate, aggregation) and its history sink appends to
     * the recent-dialogue window as a {@code world_event} turn, which is the same channel third-party
     * utterances already use. The entry carries the sentence the producer rendered; this module never
     * words one of its own.
     */
    private void publishGameEventService(ModuleRuntimeContext context) {
        gameEventService = new AXGameEventService(
                new AXEventDispatcher(),
                this::appendHistoryEvent,
                env.logs()
        );
        moduleServices = context.services();
        moduleServices.register(AXGameEventService.class, gameEventService);
    }

    private void appendHistoryEvent(AXEventAppend append) {
        AXScope scope = currentWritableScope();
        AXRecentDialogueSystem currentDialogue = recentDialogueSystem;
        if (scope == null || currentDialogue == null || append == null) {
            return;
        }
        String content = append.text();
        if (content.isBlank()) {
            return;
        }
        currentDialogue.append(scope, AXRawTurn.worldEvent(scope, content, System.currentTimeMillis()));
    }

    private void withdrawGameEventService() {
        AXGameEventService current = gameEventService;
        ModuleServiceRegistry services = moduleServices;
        gameEventService = null;
        moduleServices = null;
        if (current == null) {
            return;
        }
        current.stop(System.currentTimeMillis());
        if (services != null) {
            services.unregister(AXGameEventService.class, current);
        }
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
        // Events close first so a pending aggregate still lands in the window before the checkpoint.
        withdrawGameEventService();
        if (turnOrchestrator != null) {
            turnOrchestrator.stop();
        }
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
        turnOrchestrator = null;
        delegateService = null;
        if (lifecycleActive) {
            lifecycleActive = false;
            adapter.publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), "tianshu.presence.module.ax.stopped", lifecycleStatusGeneration));
        }
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
        AXScope scope = currentWritableScope();
        if (scope != null) {
            recordThirdPartyUtterance(scope, chatMessageMapper.map(scope, payload));
        }
        context.complete(envelope.envelopeId());
    }

    /**
     * 上下文型投递处理。只把第三方话语并入近期对话上下文，不调用 LLM、不产生语音、不占用会话，
     * 也不经过 IA 仲裁。需要回答的输入必须走 {@code AX.DIALOGUE_INPUT}。
     */
    private void handleContextInput(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof DialogueContextInputPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX context input payload is invalid", null);
            return;
        }
        AXScope scope = currentWritableScope();
        if (scope != null && !payload.messageText().isBlank()) {
            recordThirdPartyUtterance(
                    scope,
                    contextInputMapper.map(scope, envelope.header().sourceId(), payload)
            );
        }
        context.complete(envelope.envelopeId());
    }

    private void recordThirdPartyUtterance(AXScope scope, AXRawTurn turn) {
        if (scope == null || turn == null || recentDialogueSystem == null) {
            return;
        }
        recentDialogueSystem.append(scope, turn);
    }

    private AXScope currentWritableScope() {
        AXScopeProvider currentScopeProvider = scopeProvider;
        if (memorySystem == null || currentScopeProvider == null) {
            return null;
        }
        AXScope scope = currentScopeProvider.currentScope();
        return scope != null && scope.writable() ? scope : null;
    }

}
