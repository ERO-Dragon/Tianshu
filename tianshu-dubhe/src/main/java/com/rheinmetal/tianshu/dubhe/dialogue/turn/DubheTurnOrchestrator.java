package com.rheinmetal.tianshu.dubhe.dialogue.turn;

import com.rheinmetal.tianshu.api.assembly.LocalPlayerIdentity;
import com.rheinmetal.tianshu.api.assembly.PlayerAuthority;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextCollector;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheMemoryWindowPolicy;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheRuntimeLlmBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheRuntimeLlmBudgetResolver;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateService;
import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheDynamicFact;
import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheDynamicFactClient;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSnapshot;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSystem;
import com.rheinmetal.tianshu.dubhe.dialogue.currentinput.DubheDialogueInputMapper;
import com.rheinmetal.tianshu.dubhe.dialogue.currentinput.DubheInputNormalizer;
import com.rheinmetal.tianshu.dubhe.dialogue.currentinput.DubheNormalizedInput;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySystem;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentLoop;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunCancellation;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunExpiry;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunOutcome;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunPolicy;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunTermination;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepContext;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepFailure;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepOutcome;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepSignal;
import com.rheinmetal.tianshu.dubhe.dialogue.output.DubheOutputContext;
import com.rheinmetal.tianshu.dubhe.dialogue.output.DubheOutputProcessor;
import com.rheinmetal.tianshu.dubhe.core.maintenance.DubheRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeProvider;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.DubheMemoryRetrievalRequest;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.DubheMemoryRetrievalResult;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.DubheMemoryRetriever;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.dubhe.dialogue.input.DubheDialogueInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptStreamChunkPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import com.rheinmetal.tianshu.dubhe.DubheModule;
import com.rheinmetal.tianshu.dubhe.DubheParticipantRegistrar;
import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmClient;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.dubhe.agent.tool.DubheToolRoundHandler;
import com.rheinmetal.tianshu.dubhe.shared.failure.DubheFailureCause;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;

public final class DubheTurnOrchestrator implements DubheTurnPipeline {
    /**
     * 空答句的诊断码：模型这一轮没有任何可念的正文（只输出空白，或只输出标签）。
     *
     * <p>标签契约下不再有"违约"，因此这里是退化路径的唯一观测点：只登记轮次身份，不登记模型正文（隐私）。</p>
     */
    public static final String DIAGNOSTIC_EMPTY_ANSWER = "DUBHE_ASSISTANT_ANSWER_EMPTY";

    private final DubheScopeProvider scopeProvider;
    private final DubheDialogueInputMapper dialogueInputMapper;
    private final DubheInputNormalizer inputNormalizer;
    private final DubheRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private final DubheDynamicFactClient dynamicFactClient;
    private final DubheContextCollector contextCollector;
    private final DubheLlmPromptRequestBuilder llmRequestBuilder;
    private final DubheRuntimeLlmBudgetResolver budgetResolver;
    private final DubheRuntimeLlmBudget fallbackRuntimeBudget;
    private final DubheLlmClient llmClient;
    private final DubheSessionController sessionController;
    private final DubheMemorySystem memorySystem;
    private final DubheRecentDialogueSystem recentDialogueSystem;
    private final DubheOutputProcessor outputProcessor;
    private final DubheMemoryRetriever memoryRetriever;
    private final DubheTurnStatusPublisher statusPublisher;
    private final boolean allowInterruption;
    private final DiagnosticSink diagnostics;
    /**
     * Where one agent run is executed. Production is {@code DubheProtocolAdapter.submitAgentTurn}
     * ({@code LONG} lane, {@code :agent} concurrency key); tests inject an executor. Never inline:
     * the run blocks waiting for a result that the same thread would have to deliver (design §12.8).
     */
    private final DubheAgentRunSubmitter agentRunSubmitter;
    /**
     * Async delegation entry point (design §5.1 step ③). {@code null} means "not wired" (tests, and hosts
     * that run without delegation): a {@code <delegate>} tag then degrades to a plain answer, so the player
     * still hears the sentence the model said before the tag instead of silence.
     */
    private final DubheDelegateService delegateService;
    /**
     * How many turns were finished by the {@code DubheStepSignal} poll fallback instead of an event. It is
     * expected to stay at 0: a rising value means the cancellation/completion signal is not reaching
     * the waiting run (design §12.3, §12.9).
     */
    private final AtomicLong pollRescuedTurns = new AtomicLong();
    private volatile boolean accepting = true;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<DubheTurnExecution> activeExecution = new AtomicReference<>();

    public DubheTurnOrchestrator(
            DubheScopeProvider scopeProvider,
            DubheDialogueInputMapper dialogueInputMapper,
            DubheInputNormalizer inputNormalizer,
            DubheRuntimeMaintenanceCoordinator maintenanceCoordinator,
            DubheDynamicFactClient dynamicFactClient,
            DubheContextCollector contextCollector,
            DubheLlmPromptRequestBuilder llmRequestBuilder,
            DubheContextBudget contextBudget,
            DubheRuntimeLlmBudgetResolver budgetResolver,
            DubheLlmClient llmClient,
            DubheSessionController sessionController,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheOutputProcessor outputProcessor,
            DubheMemoryRetriever memoryRetriever,
            DubheAgentRunSubmitter agentRunSubmitter,
            DubheDelegateService delegateService
    ) {
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
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
                null,
                true,
                DiagnosticSink.NOOP,
                agentRunSubmitter,
                delegateService
        );
    }

    public DubheTurnOrchestrator(
            DubheScopeProvider scopeProvider,
            DubheDialogueInputMapper dialogueInputMapper,
            DubheInputNormalizer inputNormalizer,
            DubheRuntimeMaintenanceCoordinator maintenanceCoordinator,
            DubheDynamicFactClient dynamicFactClient,
            DubheContextCollector contextCollector,
            DubheLlmPromptRequestBuilder llmRequestBuilder,
            DubheContextBudget contextBudget,
            DubheRuntimeLlmBudgetResolver budgetResolver,
            DubheLlmClient llmClient,
            DubheSessionController sessionController,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheOutputProcessor outputProcessor,
            DubheMemoryRetriever memoryRetriever,
            DubheTurnStatusPublisher statusPublisher,
            DubheAgentRunSubmitter agentRunSubmitter,
            DubheDelegateService delegateService
    ) {
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
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
                statusPublisher,
                true,
                DiagnosticSink.NOOP,
                agentRunSubmitter,
                delegateService
        );
    }

    public DubheTurnOrchestrator(
            DubheScopeProvider scopeProvider,
            DubheDialogueInputMapper dialogueInputMapper,
            DubheInputNormalizer inputNormalizer,
            DubheRuntimeMaintenanceCoordinator maintenanceCoordinator,
            DubheDynamicFactClient dynamicFactClient,
            DubheContextCollector contextCollector,
            DubheLlmPromptRequestBuilder llmRequestBuilder,
            DubheContextBudget contextBudget,
            DubheRuntimeLlmBudgetResolver budgetResolver,
            DubheLlmClient llmClient,
            DubheSessionController sessionController,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheOutputProcessor outputProcessor,
            DubheMemoryRetriever memoryRetriever,
            DubheTurnStatusPublisher statusPublisher,
            boolean allowInterruption,
            DubheAgentRunSubmitter agentRunSubmitter,
            DubheDelegateService delegateService
    ) {
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
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
                statusPublisher,
                allowInterruption,
                DiagnosticSink.NOOP,
                agentRunSubmitter,
                delegateService
        );
    }

    public DubheTurnOrchestrator(
            DubheScopeProvider scopeProvider,
            DubheDialogueInputMapper dialogueInputMapper,
            DubheInputNormalizer inputNormalizer,
            DubheRuntimeMaintenanceCoordinator maintenanceCoordinator,
            DubheDynamicFactClient dynamicFactClient,
            DubheContextCollector contextCollector,
            DubheLlmPromptRequestBuilder llmRequestBuilder,
            DubheContextBudget contextBudget,
            DubheRuntimeLlmBudgetResolver budgetResolver,
            DubheLlmClient llmClient,
            DubheSessionController sessionController,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheOutputProcessor outputProcessor,
            DubheMemoryRetriever memoryRetriever,
            DubheTurnStatusPublisher statusPublisher,
            boolean allowInterruption,
            DiagnosticSink diagnostics,
            DubheAgentRunSubmitter agentRunSubmitter,
            DubheDelegateService delegateService
    ) {
        this.scopeProvider = Objects.requireNonNull(scopeProvider, "scopeProvider");
        this.dialogueInputMapper = Objects.requireNonNull(dialogueInputMapper, "dialogueInputMapper");
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer, "inputNormalizer");
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.dynamicFactClient = dynamicFactClient;
        this.contextCollector = Objects.requireNonNull(contextCollector, "contextCollector");
        this.llmRequestBuilder = Objects.requireNonNull(llmRequestBuilder, "llmRequestBuilder");
        DubheContextBudget fallbackContextBudget = contextBudget == null ? DubheContextBudget.DEFAULT : contextBudget;
        this.budgetResolver = budgetResolver;
        // Construction-time fallback only: used when there is no resolver or a turn arrives before one
        // resolves. Every resolved turn replaces it through continueTurnWithBudget.
        this.fallbackRuntimeBudget = new DubheRuntimeLlmBudget(null, fallbackContextBudget);
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
        this.memorySystem = memorySystem;
        this.recentDialogueSystem = recentDialogueSystem;
        this.outputProcessor = Objects.requireNonNull(outputProcessor, "outputProcessor");
        this.memoryRetriever = memoryRetriever;
        this.statusPublisher = statusPublisher;
        this.allowInterruption = allowInterruption;
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
        this.agentRunSubmitter = Objects.requireNonNull(agentRunSubmitter, "agentRunSubmitter");
        this.delegateService = delegateService;
    }

    public DubheTurnOrchestrator(
            DubheScopeProvider scopeProvider,
            DubheDialogueInputMapper dialogueInputMapper,
            DubheInputNormalizer inputNormalizer,
            DubheRuntimeMaintenanceCoordinator maintenanceCoordinator,
            DubheDynamicFactClient dynamicFactClient,
            DubheContextCollector contextCollector,
            DubheLlmPromptRequestBuilder llmRequestBuilder,
            DubheRuntimeLlmBudgetResolver budgetResolver,
            DubheLlmClient llmClient,
            DubheSessionController sessionController,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheOutputProcessor outputProcessor,
            DubheAgentRunSubmitter agentRunSubmitter,
            DubheDelegateService delegateService
    ) {
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
                maintenanceCoordinator,
                dynamicFactClient,
                contextCollector,
                llmRequestBuilder,
                DubheContextBudget.DEFAULT,
                budgetResolver,
                llmClient,
                sessionController,
                memorySystem,
                recentDialogueSystem,
                outputProcessor,
                null,
                agentRunSubmitter,
                delegateService
        );
    }

    public void startTurn(TianshuEnvelope deliveryEnvelope, DubheDialogueInput delivery) {
        if (!accepting) {
            release(deliveryEnvelope, delivery, DialogueReleaseReason.MODULE_UNLOADED);
            return;
        }
        DubheTurnExecution previous = activeExecution.get();
        if (previous != null && !previous.isCancelled()) {
            if (!allowInterruption) {
                release(deliveryEnvelope, delivery, DialogueReleaseReason.REJECTED);
                return;
            }
            cancelExecution(previous, DubheTurnCancellation.playerInterrupted("new IA delivery arrived"));
        }
        DubheScope scope = currentScope();
        DubheRequest rawRequest = dialogueInputMapper.map(delivery);
        DubheNormalizedInput input = inputNormalizer.normalize(rawRequest);
        if (input.empty()) {
            sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_COMPLETED);
            return;
        }
        DubheTurnExecution execution = new DubheTurnExecution(generation.incrementAndGet(), deliveryEnvelope, delivery, scope);
        execution.latencyTracker(new DubheTurnLatencyTracker(
                diagnostics,
                System::nanoTime,
                delivery.sessionId(),
                delivery.turnId(),
                delivery.turnId()
        ));
        execution.latencyTracker().mark(DubheTurnLatencyStage.IA_DELIVERY);
        execution.requestKey(input.requestKey());
        activeExecution.set(execution);
        if (statusPublisher != null) {
            statusPublisher.accepted(execution);
            statusPublisher.active(execution, "PROCESSING", allowInterruption);
        }

        DubheRequest request = DubheRequest.fromNormalizedInput(input);
        if (maintenanceCoordinator != null) {
            maintenanceCoordinator.beforeQuestion(scope, request);
        }
        if (dynamicFactClient == null) {
            continueTurn(execution, deliveryEnvelope, delivery, scope, request, List.of());
            return;
        }
        dynamicFactClient.sweepExpired();
        String dynamicRequestId = dynamicFactClient.request(deliveryEnvelope, delivery, scope, request, candidates ->
                continueTurn(execution, deliveryEnvelope, delivery, scope, request, candidates));
        execution.dynamicFactRequestId(dynamicRequestId);
    }

    private void continueTurn(DubheTurnExecution execution, TianshuEnvelope deliveryEnvelope, DubheDialogueInput delivery, DubheScope scope, DubheRequest request, List<DubheDynamicFact> dynamicFacts) {
        if (!isCurrent(execution)) {
            return;
        }
        if (budgetResolver == null) {
            continueTurnWithBudget(execution, deliveryEnvelope, delivery, scope, request, dynamicFacts, fallbackRuntimeBudget);
            return;
        }
        budgetResolver.resolveContextBudget(request.requestKey(), budget ->
                continueTurnWithBudget(execution, deliveryEnvelope, delivery, scope, request, dynamicFacts, budget));
    }

    private void continueTurnWithBudget(DubheTurnExecution execution, TianshuEnvelope deliveryEnvelope, DubheDialogueInput delivery, DubheScope scope, DubheRequest request, List<DubheDynamicFact> dynamicFacts, DubheRuntimeLlmBudget runtimeBudget) {
        if (!isCurrent(execution)) {
            return;
        }
        DubheRuntimeLlmBudget budget = runtimeBudget == null ? fallbackRuntimeBudget : runtimeBudget;
        // The resolved budget is the per-turn truth: it wins over the construction-time fallback for
        // everything this turn touches, including the recent-dialogue retention window.
        applyWindowPolicy(budget.memoryPolicy());
        if (memoryRetriever == null) {
            submitLlmTurn(execution, deliveryEnvelope, delivery, scope, request, dynamicFacts, DubheMemoryRetrievalResult.empty(), budget.contextBudget());
            return;
        }
        if (statusPublisher != null) {
            statusPublisher.active(execution, "MEMORY_RETRIEVING", allowInterruption);
        }
        memoryRetriever.retrieve(
                new DubheMemoryRetrievalRequest(
                        scope,
                        request,
                        budget.contextBudget().maxRetrievedMemoryItems(),
                        budget.contextBudget().memoryRetrievalTokenBudget(),
                        budget.memoryPolicy().tier()
                ),
                memory -> submitLlmTurn(execution, deliveryEnvelope, delivery, scope, request, dynamicFacts, memory, budget.contextBudget())
        );
    }

    /**
     * Hands the per-turn window policy to the retention buffer.
     *
     * <p>The raw dialogue window is built once with the construction-time fallback policy, so without
     * this call its retention and compression watermarks would stay frozen at the reference budget no
     * matter how large the model window is. Re-applying the per-turn policy is what makes the retained
     * history follow the real context size.</p>
     */
    private void applyWindowPolicy(DubheMemoryWindowPolicy memoryPolicy) {
        if (memoryPolicy == null) {
            return;
        }
        if (recentDialogueSystem != null) {
            recentDialogueSystem.applyWindowPolicy(memoryPolicy);
        }
        // 摘要块容量也是这份策略的一部分：不换，它就永远停在构造期兜底档（COMPACT），
        // 大窗口拿不到更大的摘要块，档位降下来时也不会跟着缩。
        if (memorySystem != null) {
            memorySystem.applyWindowPolicy(memoryPolicy);
        }
    }

    private void submitLlmTurn(DubheTurnExecution execution, TianshuEnvelope deliveryEnvelope, DubheDialogueInput delivery, DubheScope scope, DubheRequest request, List<DubheDynamicFact> dynamicFacts, DubheMemoryRetrievalResult memoryRetrieval, DubheContextBudget contextBudget) {
        if (!isCurrent(execution)) {
            return;
        }
        DubheContextSnapshot context = contextCollector.collect(scope, request, dynamicFacts);
        if (memoryRetrieval != null && !memoryRetrieval.blocks().isEmpty()) {
            context = new DubheContextSnapshot(
                    context.scope(),
                    context.memory().withRetrievedPlayerMemoryBlocks(memoryRetrieval.blocks()),
                    context.recentDialogue(),
                    context.dynamicFacts(),
                    context.deliverySnapshot()
            );
        }
        LLMPromptRequestPayload llmPayload = llmRequestBuilder.buildChatRequest(request, context, contextBudget);
        execution.latencyTracker().mark(DubheTurnLatencyStage.PROMPT_READY);
        extendSessionIfNeeded(deliveryEnvelope, delivery);
        // 水位取在这一行之前：本轮写进历史的东西（玩家的话，以及除游戏事件外的一切）都归这一轮，
        // 一个字都没答出来时整段撤掉。见 PendingTurn#rollbackHistory。
        int historyMark = recentDialogueSystem == null ? -1 : recentDialogueSystem.mark(scope);
        appendTurn(scope, "user", request.userText(), delivery.sessionId(), delivery.turnId());
        DubheOutputProcessor.DubheOutputTurn outputTurn = outputProcessor.startTurn(
                deliveryEnvelope,
                DubheOutputContext.from(delivery),
                true,
                () -> execution.latencyTracker().mark(DubheTurnLatencyStage.TTS_SUBMITTED)
        );
        if (statusPublisher != null) {
            statusPublisher.active(execution, "THINKING", allowInterruption);
        }
        execution.latencyTracker().mark(DubheTurnLatencyStage.LLM_SUBMITTED);
        // Design §12.2: the callbacks keep every existing duty (streaming, TTS, persistence, release)
        // and only signal the step at their last line; this method therefore does not block. The wait
        // for the envelope happens on the submitter's long-running thread, never on the caller's lane.
        DubheStepSignal<LLMPromptResultPayload> step = new DubheStepSignal<>();
        PendingTurn pending = new PendingTurn(execution, deliveryEnvelope, delivery, scope, outputTurn, step, historyMark);
        // A3: the main dialogue is tool-free until the Tianshu phase. The policy is the source of truth; when a
        // later phase flips toolsAllowed, the round handler comes back without touching this method again.
        DubheToolRoundHandler toolRound = DubheRunPolicy.simple().toolsAllowed()
                ? DubheToolRoundHandler.prepare(llmPayload, pending,
                        (payload, handler) -> submitAgentRequest(execution, deliveryEnvelope, payload, handler),
                        pending::discardHeldStream, toolCaller(execution))
                : null;
        LLMPromptRequestPayload firstPayload = toolRound == null ? llmPayload : toolRound.firstPayload(llmPayload);
        DubheLlmRequestHandler firstHandler = toolRound == null ? pending : toolRound;
        // Clear first so a stale id from a previous turn can never be cancelled (§12.2).
        execution.llmRequestId("");
        try {
            agentRunSubmitter.submit(
                    execution.activityId(),
                    () -> runAgentTurn(execution, deliveryEnvelope, firstPayload, firstHandler, step)
            );
        } catch (RuntimeException rejected) {
            // The run never started (lane saturated or shut down). Retire the turn now instead of
            // leaving it to the 60s envelope/lease expiry with no envelope in flight.
            cancelExecution(execution, new DubheTurnCancellation(messageOf(rejected), DialogueReleaseReason.OWNER_FAILED));
        }
    }

    /**
     * Runs the single main-dialogue step of one turn under {@link DubheRunPolicy#simple()} (§12.2).
     *
     * <p>The loop owns only the step count, the deadline and the terminal fallback; the step itself is
     * "submit the CHAT request, then wait for its signal with a bounded timeout". Everything the player
     * sees is still produced by {@link PendingTurn} on the response-delivery thread.</p>
     */
    private void runAgentTurn(
            DubheTurnExecution execution,
            TianshuEnvelope deliveryEnvelope,
            LLMPromptRequestPayload llmPayload,
            DubheLlmRequestHandler handler,
            DubheStepSignal<LLMPromptResultPayload> step
    ) {
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.simple());
        DubheRunOutcome<LLMPromptResultPayload> outcome = loop.run(
                context -> nextStep(execution, deliveryEnvelope, llmPayload, handler, step, context),
                DubheRunCancellation.of(
                        () -> execution.cancellationIfCancelled() != null,
                        execution::cancellationIfCancelled
                )
        );
        if (step.pollRescuedCount() > 0) {
            pollRescuedTurns.incrementAndGet();
        }
        finishAgentTurn(execution, outcome);
    }

    /**
     * One step: submit the request, then wait for the step signal or the remaining loop budget.
     *
     * <p>Cancellation is re-read on both sides of the submit so that neither "cancelled before the
     * request went out" nor "cancelled between submit and id assignment" can leave a live envelope
     * behind (§12.3).</p>
     */
    private DubheStepOutcome<LLMPromptResultPayload> nextStep(
            DubheTurnExecution execution,
            TianshuEnvelope deliveryEnvelope,
            LLMPromptRequestPayload llmPayload,
            DubheLlmRequestHandler handler,
            DubheStepSignal<LLMPromptResultPayload> step,
            DubheStepContext context
    ) throws InterruptedException, TimeoutException {
        DubheTurnCancellation earlyCancel = execution.cancellationIfCancelled();
        if (earlyCancel != null) {
            // Cancelled before submit: do not send the request at all.
            return new DubheStepOutcome.Aborted<>(earlyCancel, DubheRunExpiry.NONE);
        }
        TianshuEnvelope llmEnvelope = llmClient.submitInTurn(deliveryEnvelope, llmPayload, handler);
        execution.llmRequestId(llmEnvelope == null ? "" : llmEnvelope.envelopeId());
        DubheTurnCancellation lateCancel = execution.cancellationIfCancelled();
        if (lateCancel != null && !execution.llmRequestId().isBlank()) {
            // Cancelled after submit: cancel this envelope now, then still await its terminal signal.
            llmClient.cancelRequestAwaitTerminal(execution.llmRequestId(), lateCancel);
        }
        return step.await(context.remainingMillis(), context.cancellation());
    }

    /** One request on behalf of the tool round handler; the latest id is what cancellation must target. */
    private void submitAgentRequest(DubheTurnExecution execution, TianshuEnvelope deliveryEnvelope,
                                    LLMPromptRequestPayload payload, DubheLlmRequestHandler handler) {
        if (!isCurrent(execution)) {
            return;
        }
        TianshuEnvelope envelope = llmClient.submitInTurn(deliveryEnvelope, payload, handler);
        execution.llmRequestId(envelope == null ? "" : envelope.envelopeId());
    }

    /**
     * The only place the run reacts to its own terminal (§12.5).
     *
     * <p>{@code COMPLETED} means the callback already finished the turn, so there is nothing left to do.
     * Every other terminal funnels into the existing {@link #cancelExecution} — the single cancellation
     * entry point — but only if the turn is still current: {@code cancelExecution} itself does not check
     * whether the turn already ended, and re-running it would repeat {@code outputProcessor.interrupt}
     * and the status publications.</p>
     */
    private void finishAgentTurn(DubheTurnExecution execution, DubheRunOutcome<LLMPromptResultPayload> outcome) {
        if (outcome.termination() == DubheRunTermination.COMPLETED) {
            return;
        }
        if (!isCurrent(execution)) {
            return;
        }
        cancelExecution(execution, cancellationOf(outcome));
    }

    /**
     * Maps a non-completed terminal to the cancellation reason used to release the session (§12.5).
     *
     * <p>An elapsed loop deadline must actively cancel the envelope; otherwise the request keeps its
     * pending slot for the full 60s envelope expiry even though its result will be discarded.</p>
     */
    private DubheTurnCancellation cancellationOf(DubheRunOutcome<LLMPromptResultPayload> outcome) {
        DubheRunTermination termination = outcome.termination();
        if (termination == DubheRunTermination.CANCELLED) {
            return outcome.cancellation();
        }
        if (termination == DubheRunTermination.ABORT_TIMEOUT) {
            return DubheTurnCancellation.expired(
                    "AX run " + outcome.expiry().name() + " after " + outcome.elapsedMillis() + "ms");
        }
        Throwable failure = outcome.failure();
        if (failure != null) {
            // Keeps the original throwable's own text instead of inventing one (§12.5).
            return new DubheTurnCancellation(messageOf(failure), DialogueReleaseReason.OWNER_FAILED);
        }
        return new DubheTurnCancellation("AX run terminated: " + termination.name(), DialogueReleaseReason.OWNER_FAILED);
    }

    /**
     * The identity this turn's tool calls run as: the initiating player when the turn names one, otherwise the
     * agent. Operator permission comes from the game side; without a server it is false, which keeps write tools
     * off rather than guessing.
     */
    private static ToolCaller toolCaller(DubheTurnExecution execution) {
        String playerId = execution.delivery().playerId();
        if (!looksLikePlayerId(playerId)) {
            // The scope model still carries a shared placeholder user, so fall back to the local player the client
            // knows. Without one (menu, dedicated server) the turn stays a plain agent turn.
            playerId = TianshuRuntimePorts.find(LocalPlayerIdentity.class)
                    .flatMap(LocalPlayerIdentity::playerId)
                    .orElse(null);
        }
        if (playerId == null) {
            return ToolCaller.agent(false);
        }
        String owner = playerId;
        boolean operator = TianshuRuntimePorts.find(PlayerAuthority.class)
                .map(authority -> authority.operator(owner))
                .orElseGet(() -> TianshuRuntimePorts.find(LocalPlayerIdentity.class)
                        .map(LocalPlayerIdentity::operator)
                        .orElse(false));
        return ToolCaller.player(owner, operator);
    }

    private static boolean looksLikePlayerId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            java.util.UUID.fromString(value.trim());
            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * Turns finished by polling rather than by an event. Expected to stay 0; a growing value means the
     * completion/cancellation signal is not reaching the waiting run (design §12.3, §12.9).
     */
    public long pollRescuedTurns() {
        return pollRescuedTurns.get();
    }

    private void extendSessionIfNeeded(TianshuEnvelope deliveryEnvelope, DubheDialogueInput delivery) {
        long expireAt = delivery == null ? 0L : delivery.expireAtMillis();
        if (expireAt > 0L && expireAt - System.currentTimeMillis() < 3_000L) {
            sessionController.extend(deliveryEnvelope, delivery, 10_000L);
        }
    }

    private DubheScope currentScope() {
        DubheScope scope = scopeProvider.currentScope();
        return scope == null ? DubheScope.unknown() : scope;
    }

    /**
     * Whether one LLM turn is currently running.
     *
     * <p>Read only, and deliberately about the turn itself: the event layer asks this to decide whether an immediate
     * event may interrupt or has to wait for the next lull. It must not be approximated from activity, playback or
     * module state — those outlive the turn, and an event would then queue behind something that already ended.
     */
    public boolean hasActiveTurn() {
        return activeExecution.get() != null;
    }

    public void interruptActiveTurn(DubheTurnCancellation cancellation) {
        DubheTurnExecution execution = activeExecution.get();
        if (execution != null) {
            cancelExecution(execution, cancellation == null
                    ? DubheTurnCancellation.playerInterrupted("AX turn interrupted")
                    : cancellation);
        }
    }

    public void stop() {
        accepting = false;
        DubheTurnExecution execution = activeExecution.get();
        generation.incrementAndGet();
        if (execution != null) {
            cancelExecution(execution, DubheTurnCancellation.moduleUnloaded("AX module stopped"));
        }
        activeExecution.compareAndSet(execution, null);
    }

    private boolean isCurrent(DubheTurnExecution execution) {
        return execution != null && activeExecution.get() == execution && execution.isCurrent(generation);
    }

    private void cancelExecution(DubheTurnExecution execution, DubheTurnCancellation cancellation) {
        if (execution == null || !execution.cancel(cancellation)) {
            return;
        }
        persistDisplayedText(execution);
        outputProcessor.interrupt(cancellation.releaseReason().name());
        if (dynamicFactClient != null) {
            dynamicFactClient.cancelRequest(execution.dynamicFactRequestId(), cancellation.message());
        }
        if (memoryRetriever != null) {
            memoryRetriever.cancel(execution.requestKey(), cancellation.message());
        }
        if (budgetResolver != null) {
            budgetResolver.cancel(execution.requestKey(), cancellation.message());
        }
        if (!execution.llmRequestId().isBlank()) {
            llmClient.cancelRequestAwaitTerminal(execution.llmRequestId(), cancellation);
        }
        if (statusPublisher != null && activeExecution.get() == execution) {
            statusPublisher.interrupted(execution);
            statusPublisher.terminal(execution, cancellation.releaseReason());
        }
        if (execution.requestRelease()) {
            sessionController.release(execution.deliveryEnvelope(), execution.delivery(), cancellation.releaseReason());
        }
        activeExecution.compareAndSet(execution, null);
    }

    private void release(
            TianshuEnvelope deliveryEnvelope,
            DubheDialogueInput delivery,
            DialogueReleaseReason reason
    ) {
        sessionController.release(deliveryEnvelope, delivery, reason);
    }

    private void finish(DubheTurnExecution execution, DialogueReleaseReason reason) {
        if (!isCurrent(execution)) {
            return;
        }
        if (statusPublisher != null) {
            statusPublisher.terminal(execution, reason);
        }
        if (execution.requestRelease()) {
            sessionController.release(execution.deliveryEnvelope(), execution.delivery(), reason);
        }
        activeExecution.compareAndSet(execution, null);
    }

    private void appendTurn(DubheScope scope, String role, String content, String iaSessionId, String iaTurnId) {
        appendTurn(scope, role, content, iaSessionId, iaTurnId, 0);
    }

    private void persistDisplayedText(DubheTurnExecution execution) {
        if (execution == null || recentDialogueSystem == null) {
            return;
        }
        String text = execution.displayedText();
        if (text == null || text.isBlank() || !execution.markAssistantPersisted()) {
            return;
        }
        DubheDialogueInput delivery = execution.delivery();
        appendTurn(execution.scope(), "assistant", text, delivery.sessionId(), delivery.turnId());
    }

    private void appendTurn(DubheScope scope, String role, String content, String iaSessionId, String iaTurnId, int tokenCount) {
        if (recentDialogueSystem == null || content == null || content.isBlank()) {
            return;
        }
        DubheRawTurn turn = DubheRawTurn.dialogue(scope, role, content, iaSessionId, iaTurnId);
        recentDialogueSystem.append(scope, tokenCount > 0 ? turn.withTokenCount(tokenCount) : turn);
    }

    private final class PendingTurn implements DubheLlmRequestHandler {
        private final DubheTurnExecution execution;
        private final TianshuEnvelope deliveryEnvelope;
        private final DubheDialogueInput delivery;
        private final DubheScope scope;
        private final DubheOutputProcessor.DubheOutputTurn outputTurn;
        private final DubheStepSignal<LLMPromptResultPayload> step;
        /** 本轮开始前的历史水位；一个字都没答出来时，撤回到这里。 */
        private final int historyMark;
        private final StringBuilder streamed = new StringBuilder();
        /**
         * 分片闸：只放行"确定不是标签"的字符（设计 §4.1）。
         *
         * <p>压住与否由它逐片判定，而不是"首字符是不是某个符号"那种一次性判定——标签可以在任意位置、也可以
         * 被切成任意两半。</p>
         */
        private final DubheReplyStreamGate replyGate = new DubheReplyStreamGate();
        private boolean respondingPublished;
        /** 是否已经向输出层交出过内容：决定收尾时要不要补写 displayedText（中断后历史里要有已念出的话）。 */
        private boolean outputStarted;

        private PendingTurn(DubheTurnExecution execution, TianshuEnvelope deliveryEnvelope, DubheDialogueInput delivery, DubheScope scope, DubheOutputProcessor.DubheOutputTurn outputTurn, DubheStepSignal<LLMPromptResultPayload> step, int historyMark) {
            this.execution = execution;
            this.deliveryEnvelope = deliveryEnvelope;
            this.delivery = delivery;
            this.scope = scope;
            this.outputTurn = outputTurn;
            this.step = step;
            this.historyMark = historyMark;
        }

        /**
         * 这一轮连一个字都没答出来时，把它写进历史的东西撤掉。
         *
         * <p>判据是<b>云端有没有给出内容</b>，不是错误类别：429、503、余额不足、连不上、内容被拒都属于
         * "这一问从来没被回答"，它不该变成对话的一部分；而已经答出内容的失败（中途断流、后面的环节坏了）
         * 属于"说过的话"，撤掉等于把玩家听到的东西从记忆里抹掉。</p>
         *
         * <p>回滚范围是本轮水位之后的一切，唯独保留期间落进来的游戏事件：那些是世界自己发生的事，
         * 与这一问成不成功无关。</p>
         */
        /** Drops a held reply that the tool round handler intercepted; nothing of it is spoken. */
        void discardHeldStream() {
            replyGate.discardHeld();
        }

        private void rollbackHistory() {
            if (recentDialogueSystem == null || historyMark < 0) {
                return;
            }
            recentDialogueSystem.rollbackTo(scope, historyMark);
        }

        /** 云端到底有没有给出内容：流式吐过、念出过、或者终值带正文，三者之一即算答过。 */
        private boolean answeredAnything(LLMPromptResultPayload payload) {
            return !streamed.isEmpty()
                    || !execution.displayedText().isBlank()
                    || (payload != null && payload.text() != null && !payload.text().isBlank());
        }

        /**
         * 流式增量：先过闸，再出声。
         *
         * <p>主对话请求带 {@code stream=true}，所以委派标签同样是分片到达的。若把分片直接送去 TTS，等
         * {@link #onResult} 判出这是标签时，玩家已经听到半截 {@code <delegate>} 了。闸门
         * （{@link DubheReplyStreamGate}）负责"压住可疑前缀、判明后再放行"，这里只把它的产出交给输出层。</p>
         */
        @Override
        public void onStreamChunk(LLMPromptStreamChunkPayload payload) {
            if (!isCurrent(execution) || payload == null || payload.finished() || payload.text().isEmpty()) {
                return;
            }
            publishRespondingOnce();
            execution.latencyTracker().mark(DubheTurnLatencyStage.LLM_FIRST_TOKEN);
            streamed.append(payload.text());
            String speakable = replyGate.accept(payload.text());
            if (!speakable.isEmpty()) {
                appendOutput(speakable, "output.stream_failed");
            }
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
            if (!isCurrent(execution)) {
                signal(new DubheStepOutcome.Rejected<>(new DubheStepFailure(resultFailureReason(payload))));
                return;
            }
            if (payload != null && payload.isCompleted()) {
                DubheReplyContract reply = DubheReplyContract.parse(finalText(payload));
                // 先把闸里压住的部分放出去，再把终值里还没念的差额补上（流可能提前结束）。
                releaseOutput(reply.spoken());
                if (reply instanceof DubheReplyContract.Delegated delegated && delegateService != null) {
                    // 委派不是答句：标签内容绝不念给玩家（§4.1）。本轮只登记 ticket 并立即结束
                    // （§5.1 ⑤ ≤1s 出声），检索结果稍后走 §6 的投递通道回来。
                    // 收尾仍要把"标签前那句话"交出去：没有流式分片时（提供方不流式）它是唯一的出声机会。
                    if (!completeOutput(delegated.spoken())) {
                        finish(execution, DialogueReleaseReason.OWNER_FAILED);
                        signal(new DubheStepOutcome.Rejected<>(new DubheStepFailure(resultFailureReason(payload))));
                        return;
                    }
                    // 那句话已经念出去了，历史里必须有同一句（结论随后以 <event> 条目到达）。
                    appendAssistantVisibleText(delegated.spoken(), payload);
                    delegateService.delegate(scope, delivery.sessionId(), delegated.query());
                    finish(execution, DialogueReleaseReason.OWNER_COMPLETED);
                    signal(new DubheStepOutcome.Finished<>(payload));
                    return;
                }
                // 委派通道没接线：退化为普通答句——只说模型自己那句话，不静默、不念标签。
                emitAnswer(spokenAnswer(reply.spoken()), payload);
                return;
            }
            if (answeredAnything(payload)) {
                if (!execution.displayedText().isBlank()) {
                    appendAssistantVisibleText(execution.displayedText(), payload);
                }
            } else {
                // 一个字都没答出来：这一问与它带来的东西都不属于这段对话。
                rollbackHistory();
            }
            if (statusPublisher != null) {
                statusPublisher.failed(execution, resultFailureReason(payload), causeOf(payload));
            }
            failOutput(payload == null ? "LLM returned no result" : payload.errorMessage(), "output.failure_notice_failed");
            finish(execution, DialogueReleaseReason.OWNER_FAILED);
            signal(new DubheStepOutcome.Rejected<>(new DubheStepFailure(resultFailureReason(payload))));
        }

        @Override
        public void onCancellationResult(LLMPromptResultPayload payload) {
            appendAssistantVisibleText(execution.displayedText(), payload);
            signalCancelled();
        }

        @Override
        public void onCancelled(DubheTurnCancellation cancellation) {
            if (!isCurrent(execution)) {
                // This turn is no longer current, so the callback itself must stay a no-op: whoever
                // cancelled the turn already did the wrap-up. Only wake the waiting run — it needs
                // nothing but its terminal, and polling would otherwise have to discover this 50ms later.
                signalCancelled();
                return;
            }
            DubheTurnCancellation effective = cancellation == null
                    ? DubheTurnCancellation.playerInterrupted("AX turn cancelled")
                    : cancellation;
            if (!answeredAnything(null)) {
                // 打断发生在答出来之前：这一问没有留下任何内容，历史里也不该留下它。
                rollbackHistory();
            }
            if (statusPublisher != null) {
                if (effective.releaseReason() == DialogueReleaseReason.PLAYER_CANCELLED) {
                    statusPublisher.interrupted(execution);
                } else {
                    statusPublisher.failed(
                            execution,
                            "cancelled." + effective.releaseReason().name(),
                            DubheFailureCause.CANCELLED
                    );
                }
            }
            failOutput(effective.message(), "output.cancel_notice_failed");
            finish(execution, effective.releaseReason());
            signal(new DubheStepOutcome.Aborted<>(effective, expiryOf(effective)));
        }

        /** Wakes the waiting run with this step's terminal; idempotent, so racing signals are safe. */
        private void signal(DubheStepOutcome<LLMPromptResultPayload> outcome) {
            step.complete(outcome);
        }

        /**
         * Wakes the waiting run as cancelled, reusing the turn's own reason so the run never reports
         * {@code CANCELLED} without one (§12.3).
         */
        private void signalCancelled() {
            DubheTurnCancellation reason = execution.cancellationIfCancelled();
            if (reason != null) {
                signal(new DubheStepOutcome.Aborted<>(reason, expiryOf(reason)));
            }
        }

        /**
         * Distinguishes the two external clocks that can end a turn with {@code EXPIRED} (§12.4 ②③).
         *
         * <p>The discriminator is local and deterministic instead of a guess: if the IA delivery lease
         * is already past, the lease expired; otherwise the provider envelope did.</p>
         */
        private DubheRunExpiry expiryOf(DubheTurnCancellation cancellation) {
            if (cancellation == null || cancellation.releaseReason() != DialogueReleaseReason.EXPIRED) {
                return DubheRunExpiry.NONE;
            }
            long expireAt = delivery == null ? 0L : delivery.expireAtMillis();
            if (expireAt > 0L && expireAt <= System.currentTimeMillis()) {
                return DubheRunExpiry.SESSION_LEASE;
            }
            return DubheRunExpiry.LLM_ENVELOPE;
        }

        private void publishRespondingOnce() {
            if (respondingPublished || statusPublisher == null || !isCurrent(execution)) {
                return;
            }
            respondingPublished = true;
            statusPublisher.active(execution, "RESPONDING", allowInterruption);
        }

        private String resultFailureReason(LLMPromptResultPayload payload) {
            if (payload == null) {
                return "llm.result.missing";
            }
            if (payload.errorCode() != null && !payload.errorCode().isBlank()) {
                return "llm." + payload.errorCode();
            }
            return "llm." + payload.status().toLowerCase(java.util.Locale.ROOT);
        }

        private String finalText(LLMPromptResultPayload payload) {
            String resultText = payload == null ? "" : payload.text();
            return resultText == null || resultText.isBlank() ? streamed.toString() : resultText;
        }

        /**
         * 交付一句答句并结束本轮：播报 / UI / 历史只认这里收到的 {@code answerText}。
         *
         * <p>两种来源共用同一条收尾路径——剥掉委派标签后的正文、以及空答句时的资源键兜底句。把收尾收敛到一处，
         * 才能保证"标签绝不进任何通道"不依赖于每个分支各写一遍。</p>
         *
         * @param answerText 要对玩家说出口的正文
         */
        private void emitAnswer(String answerText, LLMPromptResultPayload payload) {
            String text = answerText == null ? "" : answerText;
            if (!text.isBlank()) {
                publishRespondingOnce();
            }
            if (!completeOutput(text)) {
                finish(execution, DialogueReleaseReason.OWNER_FAILED);
                signal(new DubheStepOutcome.Rejected<>(new DubheStepFailure(resultFailureReason(payload))));
                return;
            }
            appendAssistantVisibleText(text, payload);
            if (maintenanceCoordinator != null) {
                maintenanceCoordinator.afterAssistantAnswer(scope);
            }
            finish(execution, DialogueReleaseReason.OWNER_COMPLETED);
            // Last line, after the turn is already finished (§12.2 discipline 1): a COMPLETED run
            // therefore always means "the turn has been wrapped up".
            signal(new DubheStepOutcome.Finished<>(payload));
        }

        /**
         * 空答句的唯一登记点：只发轮次身份，模型原文不进诊断。
         *
         * <p>标签契约没有"违约"概念——退化路径只剩一种、且可观测：模型这一轮没有任何可念的正文。</p>
         */
        private void publishEmptyAnswer() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("sessionId", delivery.sessionId());
            attributes.put("turnId", delivery.turnId());
            diagnostics.publish(DiagnosticEvent.now(
                    DubheModule.MODULE_ID,
                    DIAGNOSTIC_EMPTY_ANSWER,
                    DiagnosticSeverity.WARN,
                    DiagnosticPrivacy.REDACTED,
                    attributes
            ));
        }

        private void appendAssistantVisibleText(String text, LLMPromptResultPayload payload) {
            if (text == null || text.isBlank() || !execution.markAssistantPersisted()) {
                return;
            }
            appendTurn(scope, "assistant", text, delivery.sessionId(), delivery.turnId(), visibleCompletionTokens(payload));
        }

        private int visibleCompletionTokens(LLMPromptResultPayload payload) {
            if (payload == null || payload.usage() == null) {
                return 0;
            }
            return Math.max(0, payload.usage().completionTokens());
        }

        /**
         * 把"已经该念、但还压在闸里"的文本放出去。
         *
         * <p>两个来源：闸门在流结束时仍压着的尾巴（普通文本，或未闭合标签的内容，见规则 4），以及终值比已发声
         * 更长的那截差额（流可能提前结束）。只在终值以已发声内容为前缀时补差额，避免把对不上的文本重复念一遍。</p>
         *
         * @param target 这一轮最终要念的正文（已剥标签）
         */
        private void releaseOutput(String target) {
            String tail = replyGate.flush();
            if (!tail.isEmpty()) {
                appendOutput(tail, "output.stream_failed");
            }
            String spoken = replyGate.emitted();
            if (target.startsWith(spoken) && target.length() > spoken.length()) {
                appendOutput(target.substring(spoken.length()), "output.final_suffix_failed");
            }
        }

        /** 要念的正文：模型说了话就念它；一个字都没有时用资源键兜底句顶上，并登记一条诊断。 */
        private String spokenAnswer(String spoken) {
            if (spoken != null && !spoken.isBlank()) {
                return spoken;
            }
            publishEmptyAnswer();
            return outputProcessor.emptyAnswerText();
        }

        private boolean appendOutput(String text, String reasonCode) {
            if (!isCurrent(execution)) {
                return false;
            }
            try {
                execution.latencyTracker().mark(DubheTurnLatencyStage.DUBHE_FIRST_SENTENCE);
                outputTurn.append(text);
                execution.appendDisplayedText(text);
                outputStarted = true;
                return true;
            } catch (RuntimeException exception) {
                publishOutputFailed(reasonCode);
                failOutput("", "output.failure_notice_failed");
                return false;
            }
        }

        private boolean completeOutput(String text) {
            if (!isCurrent(execution)) {
                return false;
            }
            try {
                outputTurn.complete(text);
                if (!outputStarted) {
                    // 一个字符都还没出去：这句就是本轮显示的全部内容（整轮被闸压住的那一轮也走这里）。
                    execution.appendDisplayedText(text);
                }
                return true;
            } catch (RuntimeException exception) {
                publishOutputFailed("output.complete_failed");
                failOutput("", "output.failure_notice_failed");
                return false;
            }
        }

        private void failOutput(String reason, String fallbackReasonCode) {
            if (!isCurrent(execution)) {
                return;
            }
            try {
                outputTurn.fail(reason == null ? "" : reason);
            } catch (RuntimeException exception) {
                publishOutputFailed(fallbackReasonCode);
            }
        }

        private void publishOutputFailed(String reasonCode) {
            if (statusPublisher != null && isCurrent(execution)) {
                // 输出通道坏了不是模型失败，分类留给词表按机器码认；认不出就是未知。
                statusPublisher.failed(execution, reasonCode, DubheFailureCause.classify(reasonCode, ""));
            }
        }

        /** 这一次失败的类别：玩家看到的原因与账本里记的原因都从这一处取。 */
        private DubheFailureCause causeOf(LLMPromptResultPayload payload) {
            if (payload == null) {
                return DubheFailureCause.UNKNOWN;
            }
            return DubheFailureCause.classify(payload.errorCode(), payload.errorMessage());
        }
    }
}
