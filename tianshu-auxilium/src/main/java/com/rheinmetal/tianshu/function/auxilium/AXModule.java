package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
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
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXReplyTextRepository;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXAccessController;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXDialogueGateway;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXSessionController;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDelivery;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDeliverySink;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateEventLedger;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegatePromptRepository;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateRegistry;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateService;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnOrchestrator;import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnStatusPublisher;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSettings;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventClass;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventService;
import com.rheinmetal.tianshu.function.auxilium.producer.AXPresenceEventMapper;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventTextProvider;
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
import com.rheinmetal.tianshu.function.auxilium.activity.AXActivityLog;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnActivityRecorder;
import com.rheinmetal.tianshu.function.auxilium.failure.AXFailureCause;
import com.rheinmetal.tianshu.function.auxilium.failure.AXRetryEntry;
import com.rheinmetal.tianshu.function.auxilium.failure.AXRetryLedger;
import com.rheinmetal.tianshu.function.auxilium.failure.AXRetryLedgerStore;
import com.rheinmetal.tianshu.function.auxilium.failure.AXRetryService;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.bridge.AXSpeechSinks;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.DefaultAXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AuxiliumStorageConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTypedTurnEnvelope;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;
import com.rheinmetal.tianshu.function.auxilium.bridge.AXDialogueChannel;
import com.rheinmetal.tianshu.function.auxilium.bridge.AXDialogueChannels;
import com.rheinmetal.tianshu.function.auxilium.contract.AXWorldEventPayload;
import com.rheinmetal.tianshu.function.auxilium.contract.DialogueContextInputPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.util.Map;

public final class AXModule implements TianshuManagedModule {
    public static final String MODULE_ID = ProtocolModuleIds.AX;

    private final IGameEnvironment env;
    private final AuxiliumStorageConfiguration storageConfiguration;
    private final ModuleRuntimeAccess runtime;
    private final AXWorldIdentityProvider worldIdentityProvider;
    private final AXPromptLanguageProvider promptLanguageProvider;
    private final AuxiliumAssistantSettings assistantSettings;
    private final AXRuntimePolicy runtimePolicy;
    private final AuxiliumOutputSettings outputSettings;
    private final AXChatOutputSink chatOutputSink;
    private final AXSpeechSink speechSink;
    /** Voice channel for delegation lines; {@code null} means this host has no voice wiring. */
    private final AXDelegateDeliverySink delegateSpeech;
    /** Voice channel for game event sentences; {@code null} means this host has no voice wiring. */
    private final AXEventSpeechSink eventSpeech;
    /** Language table the event layer renders through; {@link AXGameEventTextProvider#NONE} when unwired. */
    private final AXGameEventTextProvider eventTexts;
    private final AXProtocolAdapter adapter;
    private AXParticipantRegistrar participantRegistrar;
    private AXDialogueChannel dialogueChannel = AXDialogueChannel.NONE;
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
    /** How long a typed line stays worth answering, matching the spoken path's window. */
    private static final long TYPED_TURN_TIMEOUT_MILLIS = 30_000L;

    /**
     * 装配失败时那条模块状态的语言键。
     *
     * <p>它只说"辅星没起来"，不指路：指路的话（检查密钥/模型）只对织言成立，对辅星是误导。</p>
     */
    private static final String STARTUP_FAILED_KEY = "tianshu.presence.module.ax.startup_failed";

    private AXTurnOrchestrator turnOrchestrator;
    private InputSignals.Subscription textInputSubscription;
    private AXDelegateService delegateService;
    private AXTurnStatusPublisher turnStatusPublisher;
    private AXGameEventService gameEventService;
    private ModuleServiceRegistry moduleServices;
    /** 失败账本：还没办成、玩家还可能想再来一次的后台工作。 */
    private AXRetryLedger retryLedger;
    /** 账本对外的服务面（设置页读它、重试也走它）。 */
    private AXRetryService retryService;
    /** 界面上那半栏日志：只有这一局发生的事，重启重来一份。 */
    private AXActivityLog activityLog;
    private long lifecycleStatusGeneration;
    private boolean lifecycleActive;
    private final AXPresenceChatMessageMapper chatMessageMapper = new AXPresenceChatMessageMapper();
    private final AXDialogueContextInputMapper contextInputMapper = new AXDialogueContextInputMapper();
    private final AXPresenceWorldEventMapper worldEventMapper = new AXPresenceWorldEventMapper();

    public AXModule(IGameEnvironment env, AuxiliumStorageConfiguration storageConfiguration, ModuleRuntimeAccess runtime) {
        this(env, storageConfiguration, runtime, null);
    }

    public AXModule(IGameEnvironment env, AuxiliumStorageConfiguration storageConfiguration, ModuleRuntimeAccess runtime, AXWorldIdentityProvider worldIdentityProvider) {
        this(env, storageConfiguration, runtime, worldIdentityProvider, null, AuxiliumAssistantSettings.DEFAULT, AuxiliumOutputSettings.DEFAULT, AXChatOutputSink.NOOP);
    }

    public AXModule(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this(env, storageConfiguration, runtime, worldIdentityProvider, promptLanguageProvider, assistantSettings, AXRuntimePolicy.defaults(), outputSettings, chatOutputSink);
    }

    public AXModule(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink
    ) {
        this(env, storageConfiguration, runtime, worldIdentityProvider, promptLanguageProvider, assistantSettings,
                runtimePolicy, outputSettings, chatOutputSink, null, null);
    }

    /**
     * @param delegateSpeech voice channel for delegation lines; {@code null} keeps the diagnostic only
     * @param eventSpeech    voice channel for game event sentences; {@code null} keeps the diagnostic only
     */
    public AXModule(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink,
            AXDelegateDeliverySink delegateSpeech,
            AXEventSpeechSink eventSpeech
    ) {
        this(env, storageConfiguration, runtime, worldIdentityProvider, promptLanguageProvider, assistantSettings,
                runtimePolicy, outputSettings, chatOutputSink, delegateSpeech, eventSpeech, null);
    }

    /**
     * @param eventTexts language table the event layer renders through; {@code null} renders nothing, which
     *                   makes every event fall back to its kind code — the wiring always passes the real one
     */
    public AXModule(
            IGameEnvironment env,
            AuxiliumStorageConfiguration storageConfiguration,
            ModuleRuntimeAccess runtime,
            AXWorldIdentityProvider worldIdentityProvider,
            AXPromptLanguageProvider promptLanguageProvider,
            AuxiliumAssistantSettings assistantSettings,
            AXRuntimePolicy runtimePolicy,
            AuxiliumOutputSettings outputSettings,
            AXChatOutputSink chatOutputSink,
            AXDelegateDeliverySink delegateSpeech,
            AXEventSpeechSink eventSpeech,
            AXGameEventTextProvider eventTexts
    ) {
        this.env = env;
        this.storageConfiguration = storageConfiguration;
        this.runtime = runtime;
        this.worldIdentityProvider = worldIdentityProvider;
        this.promptLanguageProvider = promptLanguageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : promptLanguageProvider;
        this.assistantSettings = assistantSettings == null ? AuxiliumAssistantSettings.DEFAULT : assistantSettings;
        this.runtimePolicy = runtimePolicy == null ? AXRuntimePolicy.defaults() : runtimePolicy;
        this.outputSettings = outputSettings == null ? AuxiliumOutputSettings.DEFAULT : outputSettings;
        // Integrate with the speech module when it is installed, stay silent otherwise.
        this.speechSink = AXSpeechSinks.forRuntime(runtime);
        this.chatOutputSink = chatOutputSink == null ? AXChatOutputSink.NOOP : chatOutputSink;
        this.delegateSpeech = delegateSpeech;
        this.eventSpeech = eventSpeech;
        this.eventTexts = eventTexts == null ? AXGameEventTextProvider.NONE : eventTexts;
        this.adapter = new AXProtocolAdapter(runtime);
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        dialogueChannel = AXDialogueChannels.forRuntime(adapter);
        dialogueChannel.openInbound(this::handleDialogueInput);
        adapter.registerContextInputCapability(this::handleContextInput);
        adapter.registerEventInputCapability(this::handleEventInput);
        adapter.subscribePresenceWorldEvents(this::handlePresenceWorldEvent);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        if (dialogueGateway != null) {
            return;
        }
        // 两条腿各说各的：模块状态说的是"辅星的健康与生命周期"，这条产品活动说的是"玩家现在能不能用"。
        // 映迹不从模块状态反推画面，所以"准备中"必须由这里显式发出去。
        adapter.publishLoadingActivity(true);
        try {
            prepareRuntime(context);
        } catch (RuntimeException failure) {
            // 装配失败只留观察用的模块状态，不发产品状态：映迹的 ERROR 只有一句"检查密钥/服务地址/向量模型"，
            // 用在这里会把玩家指向无关的东西；而且装配抛异常的模块不会进"已准备"列表，stop()/destroy()
            // 永远不会被调，那条提示也就收不回来，只能等 TTL 到期。
            adapter.publishModuleStatus(ModuleStatuses.lifecycleFailed(
                    moduleId(), STARTUP_FAILED_KEY, lifecycleStatusGeneration));
            throw failure;
        } finally {
            // LinkageError 不经过上面的 catch，但两种情况下装配都已经结束：LOADING 必须收起，
            // 否则它要挂到 TTL 到期——模块不在已准备列表里，停机时没人替它收尾。
            adapter.publishLoadingActivity(false);
        }
    }

    /**
     * 真正的装配。
     *
     * <p>抽出来只为让 {@link #prepare} 能把"准备中/已就绪/起不来"三条产品状态收在同一处：
     * 装配路径上任何一个异常都不该让画面停在"准备中"。</p>
     */
    private void prepareRuntime(ModuleRuntimeContext context) {
        lifecycleStatusGeneration++;
        lifecycleActive = true;
        adapter.publishModuleStatus(ModuleStatuses.lifecycleStarting(moduleId(), lifecycleStatusGeneration));
        // 重投是这一层的事，诊断也跟着走这里：谁在重投、投第几遍、等多久，必须能从日志看出来。
        llmClient = new AXLlmClient(adapter, env == null ? null : env.diagnostics());
        turnStatusPublisher = new AXTurnStatusPublisher(adapter);
        retrievalPrimitiveClient = new AXLlmPrimitiveClient(adapter, runtimePolicy.retrievalPrimitiveTimeoutMillis());
        maintenancePrimitiveClient = new AXLlmPrimitiveClient(adapter, runtimePolicy.maintenancePrimitiveTimeoutMillis());
        ragClient = new AXLlmRagClient(adapter, runtimePolicy.ragTimeoutMillis());
        storageLayout = new AXStorageLayout(storageConfiguration);
        AXJsonStore jsonStore = new AXJsonStore(env);
        // 失败账本要早于它的记账点建好：记忆维护会往里记；轮次只写日志、不记账。
        retryLedger = new AXRetryLedger(new AXRetryLedgerStore(storageLayout, jsonStore));
        activityLog = new AXActivityLog();
        turnStatusPublisher.observeFailures(new AXTurnActivityRecorder(activityLog));
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
        memoryMaintenanceService.observeFailures(new AXMemoryMaintenanceService.MaintenanceObserver() {
            @Override
            public void onMaintenanceFailed(AXFailureCause cause) {
                recordFailedMaintenance(cause);
            }

            @Override
            public void onMaintenanceCompleted() {
                AXScope scope = currentWritableScope();
                AXRetryLedger ledger = retryLedger;
                if (ledger != null && scope != null) {
                    ledger.resolve(scope);
                }
                AXActivityLog log = activityLog;
                if (log != null) {
                    log.add("memory_completed", "");
                }
            }
        });
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
        AXSessionController sessionController = new AXSessionController(dialogueChannel);
        // The output layer resolves the player-visible wording of its own fallback sentences, so it gets
        // the same language source as the prompt side (both live in resources, never in Java).
        AXOutputProcessor outputProcessor = new AXOutputProcessor(
                adapter, outputSettings, chatOutputSink, new AXReplyTextRepository(promptLanguageProvider),
                speechSink);
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
        // Subscribing is the switch: the platform opens the input bar only when something listens for a typed
        // line, so a disabled feature means the key does nothing, not "a bar whose lines go nowhere".
        if (assistantSettings.textInputEnabled()) {
            textInputSubscription = InputSignals.subscribe(InputSignals.TEXT_SUBMITTED, this::handleTypedText);
        }
        dialogueGateway = new AXDialogueGateway(new AXAccessController(), turnOrchestrator, turnStatusPublisher,
                this::flushHeldEventsBeforeUserTurn);
        participantRegistrar = new AXParticipantRegistrar(dialogueChannel, assistantSettings, this::participantDisplayName);
        participantRegistrar.register();
        publishModuleServices(context);
        adapter.publishModuleStatus(ModuleStatuses.lifecycleReady(moduleId(), lifecycleStatusGeneration));
    }

    /**
     * Reports one delegation line as a diagnostic and hands it to the voice channel.
     *
     * <p>The log line stays as the trace; the sink is the single speech path (arrival order is FIFO,
     * queuing belongs to the TTS module). This layer never renders player wording: the resource key or
     * the conclusion text is passed through as it is (design §6.2).</p>
     */
    private void publishDelegateDelivery(AXDelegateDelivery delivery) {
        if (delivery == null) {
            return;
        }
        env.logs().info("ax.delegate.speech_pending task=" + delivery.taskId()
                + (delivery.utteranceKey() == null ? "" : " key=" + delivery.utteranceKey())
                + (delivery.isAck() ? " ack=true" : " kind=" + delivery.kind().code()));
        AXDelegateDeliverySink sink = delegateSpeech;
        if (sink != null) {
            sink.publish(delivery);
        }
    }

    /**
     * Publishes everything this module offers the rest of the session, in one place so the same list can be
     * withdrawn in one place.
     *
     * <ul>
     *   <li>{@link AXGameEventService} — the event decision path (tiers, the attention list, the speech gate, both
     *       holding areas) and the only one: a managed module reaches it through
     *       {@code context.services().find(...)}, a platform layer through {@code coreManager.findService(...)}.
     *       Its history sink appends to the recent-dialogue window as a {@code world_event} turn, the channel
     *       third-party utterances already use. The entry carries the sentence the producer rendered; this module
     *       never words one of its own.</li>
     *   <li>{@link AXDelegateService} — this module's own delegation runs.</li>
     * </ul>
     *
     * <p>Voicing is not a service here: it goes to the injected speech channel, next to the diagnostic log line.</p>
     */
    private void publishModuleServices(ModuleRuntimeContext context) {
        gameEventService = new AXGameEventService(
                this::appendHistoryEvent,
                eventSpeech == null ? AXEventSpeechSink.NOOP : eventSpeech,
                eventTexts,
                eventSettings(),
                env.logs()
        );
        moduleServices = context.services();
        moduleServices.register(AXGameEventService.class, gameEventService);
        moduleServices.register(AXDelegateService.class, delegateService);
        retryService = new AXRetryService(retryLedger, scopeProvider, retryAction());
        moduleServices.register(AXRetryService.class, retryService);
        moduleServices.register(AXActivityLog.class, activityLog);
    }

    /**
     * 后台工作唯一的重试方式：再触发一次记忆维护。
     *
     * <p>对话不在这张表里——问一次没成，玩家自己会再问一次，那才是新的对话；给它一个"重放上一问"的按钮，
     * 等于让天枢替玩家决定该问什么。</p>
     */
    private AXRetryService.AXRetryAction retryAction() {
        return entry -> {
            AXRuntimeMaintenanceCoordinator current = maintenanceCoordinator;
            AXScope scope = currentWritableScope();
            if (current == null || scope == null) {
                return false;
            }
            boolean accepted = current.requestMaintenanceNow(scope);
            AXActivityLog log = activityLog;
            if (accepted && log != null) {
                log.add("retry_retried", entry == null ? "" : entry.cause().code());
            }
            return accepted;
        };
    }

    /** 记忆维护失败了：记进账本；重试它就是再触发一次维护。 */
    private void recordFailedMaintenance(AXFailureCause cause) {
        AXRetryLedger ledger = retryLedger;
        AXScope scope = currentWritableScope();
        AXActivityLog log = activityLog;
        if (cause == null || cause == AXFailureCause.CANCELLED) {
            return;
        }
        if (log != null) {
            log.add("memory_failed", cause.code());
        }
        if (ledger == null || scope == null) {
            return;
        }
        ledger.record(scope, AXRetryEntry.memoryMaintenance(scope, cause, cause.code(), System.currentTimeMillis()));
    }

    /**
     * 启动清理：最后一轮若是一句没人答的提问，撤掉它。
     *
     * <p>崩溃时来不及回滚（进程直接没了），但那句话已经落盘。所以判据不是账本，而是历史自己：完成的轮次
     * 总以答句或游戏事件结尾，以提问结尾就意味着那一轮没走完——它不该成为下一轮对话的开头。</p>
     */
    private void pruneUnansweredTail() {
        AXRecentDialogueSystem dialogue = recentDialogueSystem;
        AXScope scope = currentWritableScope();
        if (dialogue == null || scope == null) {
            return;
        }
        if (dialogue.pruneUnansweredTail(scope)) {
            AXActivityLog log = activityLog;
            if (log != null) {
                log.add("history_pruned", "");
            }
        }
    }

    /**
     * Tunables of the event layer.
     *
     * <p>The interruption permission is the player's own setting. The busy signal is the turn pipeline's existing
     * in-flight state ({@link AXTurnOrchestrator#hasActiveTurn()}), not an approximation: an immediate event may only
     * wait for a turn that is really running, and must not queue behind playback or activity that outlives it.
     */
    private AXEventSettings eventSettings() {
        return new AXEventSettings() {
            @Override
            public boolean allowInterruption() {
                return assistantSettings.allowInterruption();
            }

            @Override
            public boolean busy() {
                AXTurnOrchestrator current = turnOrchestrator;
                return current != null && current.hasActiveTurn();
            }
        };
    }

    /**
     * Turns everything the event layer is holding into history, in arrival order, before the player's own line.
     *
     * <p>Wired into the dialogue gateway, so it runs once per accepted player delivery: that is the moment "the user
     * spoke first" becomes true, and the only moment where the held lines still precede the player's line in the
     * history.
     */
    private void flushHeldEventsBeforeUserTurn() {
        AXGameEventService current = gameEventService;
        if (current != null) {
            current.beforeUserTurn(System.currentTimeMillis());
        }
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

    /**
     * Withdraws every service published by {@link #publishModuleServices}.
     *
     * <p>A stopped module must not stay reachable by type: an entry left behind makes
     * {@code findService(...).isPresent()} answer "yes" for something that is no longer running, and the object
     * behind it is a closed one. The event service is stopped here too, which is what turns its last held lines into
     * history instead of dropping them.</p>
     */
    private void withdrawModuleServices() {
        AXGameEventService currentEvents = gameEventService;
        AXDelegateService currentDelegates = delegateService;
        AXRetryService currentRetries = retryService;
        AXActivityLog currentLog = activityLog;
        ModuleServiceRegistry services = moduleServices;
        gameEventService = null;
        retryService = null;
        moduleServices = null;
        if (services != null) {
            if (currentLog != null) {
                services.unregister(AXActivityLog.class, currentLog);
            }
            if (currentRetries != null) {
                services.unregister(AXRetryService.class, currentRetries);
            }
            if (currentDelegates != null) {
                services.unregister(AXDelegateService.class, currentDelegates);
            }
            if (currentEvents != null) {
                services.unregister(AXGameEventService.class, currentEvents);
            }
        }
        if (currentEvents != null) {
            currentEvents.stop(System.currentTimeMillis());
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
        // 上一次退出或崩溃留下的最后一问：没人答就撤掉，否则它会成为下一轮对话的开头。
        pruneUnansweredTail();
    }

    @Override
    public void stop() {
        // Events close first so a pending aggregate still lands in the window before the checkpoint.
        withdrawModuleServices();
        if (textInputSubscription != null) {
            textInputSubscription.close();
            textInputSubscription = null;
        }
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
            adapter.publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleStatusGeneration));
        }
        // 停机不留尾巴：一条没被收起的"准备中"会跟着玩家离开这个世界。
        adapter.publishLoadingActivity(false);
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
        retryLedger = null;
        retryService = null;
        activityLog = null;
    }

    /**
     * A typed line: the player pressed the text key, wrote it, and submitted it.
     *
     * <p>Nothing recognized the words, so there are no object matches and no repaired form — the text is used
     * exactly as written. The module collects the world itself when the turn runs, which is why the input is
     * built without a context snapshot.</p>
     */
    private void handleTypedText(String text) {
        AXTurnOrchestrator orchestrator = turnOrchestrator;
        if (orchestrator == null || text == null || text.isBlank()) {
            return;
        }
        AXScope scope = currentWritableScope();
        AXDialogueInput input = AXDialogueInput.typed(
                scope.sharedUserId(), text, System.currentTimeMillis() + TYPED_TURN_TIMEOUT_MILLIS);
        orchestrator.startTurn(AXTypedTurnEnvelope.selfEnvelope(input), input);
    }

    /**
     * A spoken turn, already translated by the bridge into this module's input type.
     *
     * <p>Whether the dialogue domain is installed is decided once, when the channel is chosen; this method
     * runs only if it is, so it carries no optionality.</p>
     */
    private void handleDialogueInput(TianshuEnvelope envelope, AXDialogueInput input, ProtocolContext context)
            throws Exception {
        AXDialogueGateway currentGateway = dialogueGateway;
        if (currentGateway == null) {
            context.fail(envelope.envelopeId(), "AX_NOT_READY", "AX runtime is not prepared", null);
            return;
        }
        env.diagnostics().publish(DiagnosticEvent.now(
                MODULE_ID,
                "DIALOGUE_DELIVERY",
                DiagnosticSeverity.INFO,
                DiagnosticPrivacy.RAW_CONTENT,
                Map.of(
                        "sessionId", input.sessionId(),
                        "turnId", input.turnId(),
                        "text", input.text()
                )
        ));
        currentGateway.handleDelivery(envelope, input, context);
    }

    private void handlePresenceWorldEvent(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof PresenceWorldEventPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX presence world event payload is invalid", null);
            return;
        }
        AXScopeProvider currentScopeProvider = scopeProvider;
        AXScope scope = currentScopeProvider == null ? null : currentScopeProvider.currentScope();
        if (PresenceWorldEventPayload.EVENT_CHAT_MESSAGE.equals(payload.eventType())) {
            // A chat line is something said, not something remembered: it belongs to the recent dialogue.
            if (scope != null && scope.writable()) {
                recordThirdPartyUtterance(scope, chatMessageMapper.map(scope, payload));
            }
            context.complete(envelope.envelopeId());
            return;
        }
        // 一条事实只有一个落点：能翻成事件类型的走事件层（它自己写历史，并按档位决定出不出声），
        // 其余类型作为记忆附件留给压缩链路。一个载荷带一秒窗口的多组出现，合成一行提交。
        java.util.Optional<AXPresenceEventMapper.Batch> batch = AXPresenceEventMapper.batch(payload);
        if (batch.isPresent()) {
            AXGameEventService currentEvents = gameEventService;
            if (currentEvents != null && !currentEvents.closed()) {
                currentEvents.submitBatch(envelope.header().sourceId(), batch.get(), System.currentTimeMillis());
            }
            context.complete(envelope.envelopeId());
            return;
        }
        AXMemorySystem currentMemory = memorySystem;
        if (currentMemory != null && scope != null && scope.writable()) {
            currentMemory.appendAttachedWorldEvent(scope, worldEventMapper.map(scope, payload));
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
        if (scope == null) {
            // 没有可写作用域时不能"接受但不写"：静默成功会让投递方以为话语已经进了历史。
            context.fail(envelope.envelopeId(), "AX_NOT_READY", "AX context input has no writable scope", null);
            return;
        }
        if (!payload.messageText().isBlank()) {
            recordThirdPartyUtterance(
                    scope,
                    contextInputMapper.map(scope, envelope.header().sourceId(), payload)
            );
        }
        context.complete(envelope.envelopeId());
    }

    /**
     * 事件型投递处理：外部模块给一句自己渲染好的世界事件，加一个它希望的行为。
     *
     * <p>本模块不认这句话的措辞，只做清洗并把它交给事件层，因此它和内建事件走同一条判定路径：
     * 行为先被翻译成档位声明，再由频率修正、名单与类别决定最终是立即出声 / 等冷却 / 只进历史 / 丢弃。
     * 频率不对外暴露：只有内置采集层知道"这个 kind 多久一次"。</p>
     *
     * <p>没有运行中的事件层就拒绝，不静默接受：投递方必须知道这次没落地。</p>
     */
    private void handleEventInput(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AXWorldEventPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX world event payload is invalid", null);
            return;
        }
        AXGameEventService service = gameEventService;
        if (service == null || service.closed()) {
            context.fail(envelope.envelopeId(), "AX_NOT_READY", "AX event layer is not running", null);
            return;
        }
        if (payload.behavior() == null || payload.behavior() == AXEventClass.DROP) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD",
                    "AX world event behavior must be IMMEDIATE, DEFERRED or SILENT", null);
            return;
        }
        if (payload.text().isBlank()) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX world event text is empty", null);
            return;
        }
        try {
            service.submitRendered(envelope.header().sourceId(), payload.kind(), payload.behavior(), payload.text());
        } catch (IllegalArgumentException malformed) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", malformed.getMessage(), malformed);
            return;
        }
        context.complete(envelope.envelopeId());
    }

    /**
     * IA 显示这个 participant 用的名字，从语言表解析。
     *
     * <p>它以前拿的是唤醒词：玩家没配唤醒词时，UI 与诊断里就出现一个没有名字的 participant。
     * 名字是界面文本，与其他界面文本一样来自语言分片。</p>
     */
    private String participantDisplayName() {
        return eventTexts.render(AXEventText.of(AXParticipantRegistrar.DISPLAY_NAME_KEY));
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
