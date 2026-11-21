package com.rheinmetal.tianshu.dubhe.core.turn;

import com.rheinmetal.tianshu.dubhe.bridge.IaDialogueBridge;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.dubhe.DubheModule;
import com.rheinmetal.tianshu.dubhe.DubheParticipantRegistrar;
import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.core.agent.DubheAgentLoop;
import com.rheinmetal.tianshu.dubhe.core.agent.DubheRunCancellation;
import com.rheinmetal.tianshu.dubhe.core.agent.DubheRunOutcome;
import com.rheinmetal.tianshu.dubhe.core.agent.DubheRunPolicy;
import com.rheinmetal.tianshu.dubhe.core.agent.DubheRunTermination;
import com.rheinmetal.tianshu.dubhe.core.delegate.DubheDelegateDelivery;
import com.rheinmetal.tianshu.dubhe.core.delegate.DubheDelegateEventLedger;
import com.rheinmetal.tianshu.dubhe.core.delegate.DubheDelegateRegistry;
import com.rheinmetal.tianshu.dubhe.core.delegate.DubheDelegateService;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextCollector;
import com.rheinmetal.tianshu.dubhe.core.context.DubheMemoryWindowPolicy;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmClient;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.dubhe.core.output.DubheChatOutputSink;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputContext;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputMode;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputProcessor;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputSettings;
import com.rheinmetal.tianshu.dubhe.core.output.DubheReplyTextRepository;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptOrchestrator;
import com.rheinmetal.tianshu.dubhe.module.currentinput.DubheDialogueInputMapper;
import com.rheinmetal.tianshu.dubhe.module.currentinput.DubheInputNormalizer;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSystem;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.scope.DubheScopeKind;
import com.rheinmetal.tianshu.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.dubhe.input.DubheDialogueInput;
import com.rheinmetal.tianshu.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.trigger.TriggerAccess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Production wiring of {@link DubheAgentLoop} into the main dialogue path (design §12.7, §12.8).
 *
 * <p>These are the acceptance cases: request equivalence, terminal equivalence, exactly-once release,
 * cancellation (no request before it, event wake-up after it), the 10s loop deadline and a failing
 * submit. Every case drives the turn the way production does: {@code startTurn} returns immediately and
 * the run itself happens on the submitter's own thread.</p>
 */
class DubheAgentTurnWiringTest {

    private static final DubheDialogueInputMapper MAPPER = new DubheDialogueInputMapper();
    private static final DubheInputNormalizer NORMALIZER = new DubheInputNormalizer();
    private static final DubheReplyTextRepository REPLY_TEXTS =
            new DubheReplyTextRepository(DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));

    private static final ExecutorService RUNS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ax-agent-wiring-test");
        thread.setDaemon(true);
        return thread;
    });

    @Test
    void mainDialogueRunsThroughTheLoopAndSubmitsTheSameChatRequestWithoutTools() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        DubheScope scope = writableScope();
        DubheDialogueInput delivery = delivery();
        DubheRecentDialogueSystem recent = emptyRecent();
        DubheRequest request = DubheRequest.fromNormalizedInput(NORMALIZER.normalize(MAPPER.map(delivery)));
        // The run builds its prompt before the user turn is appended, so the reference context and
        // payload are taken from the same still-empty window instead of from the window after startTurn.
        LLMPromptRequestPayload expected = new DubheLlmPromptRequestBuilder(new DubhePromptOrchestrator(null, null, null))
                .buildChatRequest(
                        request,
                        new DubheContextCollector(null, recent).collect(scope, request, List.of()),
                        DubheContextBudget.DEFAULT);
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, recent, scope, null, (taskId, task) -> RUNS.execute(task));

        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request to leave the run");

        LLMPromptRequestPayload actual = (LLMPromptRequestPayload) runtime.llmRequest.get().payload();
        assertEquals(expected, actual, "the loop must not change a single field of the prompt request");
    }

    @Test
    @org.junit.jupiter.api.Disabled("A3: main dialogue has no tools until the Tianshu phase; the tool turn is covered by DubheToolRoundHandlerTest")
    void aToolCallIsExecutedAndOnlyTheFinalAnswerIsSpoken() {
        ToolId echo = ToolId.of("test", "echo");
        TianshuTools.register(new WiringEchoTool(echo));
        try {
            TestRuntime runtime = new TestRuntime(false);
            runtime.registerLlmSink();
            DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
            DubheLlmClient llmClient = new DubheLlmClient(adapter);
            DubheScope scope = writableScope();
            DubheRecentDialogueSystem recent = emptyRecent();
            PointOfDeliverySink sink = new PointOfDeliverySink();
            DubheTurnOrchestrator orchestrator = orchestrator(
                    adapter, llmClient, recent, scope, sink, (taskId, task) -> RUNS.execute(task));

            DubheDialogueInput delivery = delivery();
            orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
            await(() -> runtime.llmRequest.get() != null, 5_000L, "the first CHAT request");

            TianshuEnvelope first = runtime.llmRequest.get();
            String firstRequestId = ((LLMPromptRequestPayload) first.payload()).requestId();
            llmClient.handleResult(first.envelopeId(), LLMPromptResultPayload.completed(firstRequestId,
                    "{\"tool\":\"test:echo\",\"args\":{\"text\":\"hi\"}}"));

            await(() -> runtime.llmRequest.get() != first, 5_000L, "the follow-up request carrying the tool result");
            TianshuEnvelope second = runtime.llmRequest.get();
            LLMPromptRequestPayload followUp = (LLMPromptRequestPayload) second.payload();
            assertTrue(followUp.messages().stream()
                            .anyMatch(message -> message.content().contains("tool result for test:echo")),
                    "the tool result must be in the conversation");
            assertTrue(sink.delivered.isEmpty(), "the call itself must not be spoken");

            llmClient.handleResult(second.envelopeId(),
                    LLMPromptResultPayload.completed(followUp.requestId(), "你好，我在。"));

            await(() -> sink.completed.get() == 1, 5_000L, "the final answer");
            assertTrue(sink.delivered.stream().anyMatch(text -> text.contains("你好")),
                    "the final answer reaches the player");
            assertTrue(sink.delivered.stream().noneMatch(text -> text.contains("test:echo")),
                    "the tool call is never spoken");
        } finally {
            TianshuTools.unregister(echo);
        }
    }

    @Test
    void completedTurnCompletesTheAssistantOnceAndReleasesOnce() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        DubheScope scope = writableScope();
        DubheRecentDialogueSystem recent = emptyRecent();
        PointOfDeliverySink sink = new PointOfDeliverySink();
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, recent, scope, sink, (taskId, task) -> RUNS.execute(task));

        DubheDialogueInput delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request");

        TianshuEnvelope request = runtime.llmRequest.get();
        llmClient.handleResult(request.envelopeId(), LLMPromptResultPayload.completed(
                ((LLMPromptRequestPayload) request.payload()).requestId(), "暮色森林从要塞旁的传送门进入。"));

        await(() -> !runtime.sessionControls.isEmpty(), 5_000L, "the session release");
        await(() -> sink.completed.get() == 1, 5_000L, "the assistant completion");

        assertEquals(1, sink.completed.get(), "the assistant text is delivered exactly once");
        assertEquals(DialogueReleaseReason.OWNER_COMPLETED, runtime.sessionControls.get(0).reason());
        assertEquals(2, recent.snapshot(scope).turns().size(), "user plus assistant, the assistant persisted once");
        assertEquals(0, orchestrator.pollRescuedTurns(), "completion must arrive as an event, not via the fallback");

        // A completed run must not release twice, even if the run thread lingers.
        sleep(300L);
        assertEquals(1, runtime.sessionControls.size(), "session release happens exactly once per turn");
    }

    @Test
    void cancelBeforeSubmitSendsNoRequestAndStillReleasesExactlyOnce() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        List<Runnable> gated = new ArrayList<>();
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> gated.add(task));

        DubheDialogueInput delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> gated.size() == 1, 5_000L, "the run to be handed to the submitter");

        orchestrator.interruptActiveTurn(DubheTurnCancellation.playerInterrupted("player spoke again"));
        assertEquals(1, runtime.sessionControls.size(), "the cancelled turn releases right away");
        assertEquals(DialogueReleaseReason.PLAYER_CANCELLED, runtime.sessionControls.get(0).reason());
        assertNull(runtime.llmRequest.get(), "a turn cancelled before submit must not send a request");

        // Running the queued run afterwards must be a no-op: the turn is already retired.
        gated.get(0).run();
        assertEquals(1, runtime.sessionControls.size(), "release stays exactly once");
        assertNull(runtime.llmRequest.get());
        assertEquals(0, orchestrator.pollRescuedTurns());
    }

    @Test
    void cancelAfterSubmitWakesTheRunByEventNotByPolling() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> RUNS.execute(task));

        DubheDialogueInput delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request");

        orchestrator.interruptActiveTurn(DubheTurnCancellation.playerInterrupted("player spoke again"));
        await(() -> !runtime.sessionControls.isEmpty(), 5_000L, "the cancellation release");
        assertEquals(DialogueReleaseReason.PLAYER_CANCELLED, runtime.sessionControls.get(0).reason());

        // Longer than one poll slice: a run woken by the 50ms fallback would already have counted itself.
        sleep(300L);
        assertEquals(0, orchestrator.pollRescuedTurns(),
                "cancelRequestAwaitTerminal must wake the run through its callback (event path)");
    }

    @Test
    void loopDeadlineRetiresTheTurnAtTheLoopBudgetInsteadOfTheEnvelopeExpiry() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> RUNS.execute(task));

        long startedAt = System.currentTimeMillis();
        DubheDialogueInput delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request");

        // No answer is ever delivered: only the loop's own deadline can retire this turn.
        await(() -> !runtime.sessionControls.isEmpty(), DubheRunPolicy.SIMPLE_TIMEOUT_MILLIS + 5_000L,
                "the loop deadline to expire the turn");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals(DialogueReleaseReason.EXPIRED, runtime.sessionControls.get(0).reason());
        assertTrue(elapsed >= DubheRunPolicy.SIMPLE_TIMEOUT_MILLIS - 500L,
                "the fallback must not fire before the loop budget is spent");
        assertTrue(elapsed < DubheRunPolicy.SIMPLE_TIMEOUT_MILLIS + 5_000L,
                "the turn retires at the loop deadline, not at the envelope expiry");
        assertEquals(0, orchestrator.pollRescuedTurns(), "a deadline is not a poll rescue");
        assertEquals(1, runtime.sessionControls.size());
    }

    @Test
    void submitFailureReleasesOwnerFailedInsteadOfHangingTheTurn() {
        TestRuntime runtime = new TestRuntime(true);
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> RUNS.execute(task));

        DubheDialogueInput delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);

        await(() -> !runtime.sessionControls.isEmpty(), 5_000L, "a failing submit to release the turn");
        assertEquals(DialogueReleaseReason.OWNER_FAILED, runtime.sessionControls.get(0).reason(),
                "a throwing submit returns to the caller as OWNER_FAILED instead of stranding the turn");
        assertNull(runtime.llmRequest.get());
        assertEquals(1, runtime.sessionControls.size());
    }

    @Test
    void rejectedRunRetiresTheTurnInsteadOfWaitingForAnEnvelopeThatWasNeverSent() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null,
                (taskId, task) -> {
                    throw new RejectedExecutionException("lane saturated");
                });

        DubheDialogueInput delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);

        assertEquals(1, runtime.sessionControls.size(), "a rejected run retires the turn immediately");
        assertEquals(DialogueReleaseReason.OWNER_FAILED, runtime.sessionControls.get(0).reason());
        assertNull(runtime.llmRequest.get());
    }

    @Test
    void delegateReplyIsNeverSpokenAndTheTurnEndsImmediately() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        DubheLlmClient llmClient = new DubheLlmClient(adapter);
        DubheScope scope = writableScope();
        DubheRecentDialogueSystem recent = emptyRecent();
        List<Runnable> delegateRuns = new ArrayList<>();
        List<DubheDelegateDelivery> deliveries = new CopyOnWriteArrayList<>();
        DubheDelegateRegistry delegateRegistry = new DubheDelegateRegistry();
        DubheDelegateService delegateService = new DubheDelegateService(
                (payload, handler, timeout) -> {
                },
                delegateRegistry,
                null,
                null,
                new DubheDelegateEventLedger(),
                null,
                (taskId, task) -> delegateRuns.add(task),
                deliveries::add,
                LogSink.NOOP,
                System::currentTimeMillis);
        PointOfDeliverySink sink = new PointOfDeliverySink();
        DubheTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, recent, scope, sink,
                (taskId, task) -> RUNS.execute(task), delegateService);
        String spokenSentence = "这个问题让我去查一下。";

        DubheDialogueInput delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request");

        TianshuEnvelope request = runtime.llmRequest.get();
        llmClient.handleResult(request.envelopeId(), LLMPromptResultPayload.completed(
                ((LLMPromptRequestPayload) request.payload()).requestId(),
                spokenSentence + "<delegate>铁砧怎么修复工具</delegate>"));

        await(() -> !deliveries.isEmpty(), 5_000L, "the delegation acknowledgement");
        await(() -> !runtime.sessionControls.isEmpty(), 5_000L, "the turn release");

        assertTrue(deliveries.get(0).isAck(), "委派先给回执，不是答句");
        assertEquals(1, delegateRuns.size(), "委派只登记 ticket 并把检索交给后台 run（§5.1 ⑤）");
        assertNotNull(delegateRegistry.find(deliveries.get(0).taskId()), "ticket 已进注册表");
        assertEquals("铁砧怎么修复工具",
                delegateRegistry.find(deliveries.get(0).taskId()).query());
        assertEquals(DialogueReleaseReason.OWNER_COMPLETED, runtime.sessionControls.get(0).reason());
        assertEquals(1, runtime.sessionControls.size(), "本轮立即结束，release 恰好一次");
        assertTrue(sink.delivered.stream().noneMatch(text -> text.contains("delegate")),
                "标签绝不念给玩家（§4.1）");
        assertTrue(sink.delivered.stream().allMatch(text -> text.isBlank() || text.equals(spokenSentence)),
                "出声内容只有标签前那句话：" + sink.delivered);
        await(() -> recent.snapshot(scope).turns().size() == 2, 5_000L, "the assistant history entry");
        assertEquals(spokenSentence, recent.snapshot(scope).turns().get(1).content(),
                "历史里存的是念出来的那句话，不是标签");
    }

    @Test
    void delegateRunUsesItsOwnLongLaneConcurrencyKey() {
        TestRuntime runtime = new TestRuntime(false);
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);

        adapter.submitDelegateRun("ax.delegate.test-1", () -> {
        });

        ProtocolTaskSpec spec = runtime.lastSpec.get();
        assertNotNull(spec);
        assertEquals(ExecutionLane.LONG, spec.lane(), "委派等待跑在 LONG 车道，不占协议 CPU 车道");
        assertEquals(DubheProtocolAdapter.MODULE_ID + ":agent_delegate", spec.concurrencyKey(),
                "独立并发键：一次 120s 检索不得堵住主对话的 :agent");
        assertEquals(1, spec.maxConcurrency());
        assertEquals(3, spec.queueCapacity());
    }

    @Test
    void runFailureKeepsTheOriginalThrowable() {
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.simple());
        IllegalStateException boom = new IllegalStateException("llm provider unavailable");

        DubheRunOutcome<String> outcome = loop.run(context -> {
            throw boom;
        }, DubheRunCancellation.none());

        assertEquals(DubheRunTermination.FAILED, outcome.termination());
        assertSame(boom, outcome.failure(), "the failure must not be wrapped or replaced (§12.5)");
        assertNotNull(outcome.failure());
    }

    // ---------------------------------------------------------------- fixtures

    private static DubheTurnOrchestrator orchestrator(
            DubheProtocolAdapter adapter,
            DubheLlmClient llmClient,
            DubheRecentDialogueSystem recent,
            DubheScope scope,
            DubheChatOutputSink sink,
            DubheAgentRunSubmitter submitter
    ) {
        return orchestrator(adapter, llmClient, recent, scope, sink, submitter, null);
    }

    private static DubheTurnOrchestrator orchestrator(
            DubheProtocolAdapter adapter,
            DubheLlmClient llmClient,
            DubheRecentDialogueSystem recent,
            DubheScope scope,
            DubheChatOutputSink sink,
            DubheAgentRunSubmitter submitter,
            DubheDelegateService delegateService
    ) {
        DubheOutputProcessor outputProcessor = new DubheOutputProcessor(
                adapter, (DubheOutputSettings) () -> DubheOutputMode.UI_ONLY, sink, REPLY_TEXTS);
        return new DubheTurnOrchestrator(
                () -> scope,
                MAPPER,
                NORMALIZER,
                null,
                null,
                new DubheContextCollector(null, recent),
                new DubheLlmPromptRequestBuilder(new DubhePromptOrchestrator(null, null, null)),
                null,
                null,
                llmClient,
                new DubheSessionController(new IaDialogueBridge(adapter)),
                null,
                recent,
                outputProcessor,
                null,
                null,
                true,
                DiagnosticSink.NOOP,
                submitter,
                delegateService
        );
    }

    private static DubheRecentDialogueSystem emptyRecent() {
        return new DubheRecentDialogueSystem(DubheMemoryWindowPolicy.fromBudget(8000, 3, 60000L));
    }

    private static DubheScope writableScope() {
        return new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);
    }

    private static DubheDialogueInput delivery() {
        return new DubheDialogueInput(
                "player",
                "session",
                "turn",
                "暮色森林怎么进？",
                DialogueContextSnapshot.empty("player"),
                DialogueInteractionHints.empty(),
                List.of(),
                java.util.Map.of(),
                List.of(),
                System.currentTimeMillis() + 30_000L
        );
    }

    private static TianshuEnvelope deliveryEnvelope(DubheDialogueInput input) {
        return EnvelopeBuilder.commandToCapability(
                IaProtocolAdapter.SOURCE_ID,
                DubheProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.of("DIALOGUE_DELIVERY"),
                deliveryPayload()
        ).build();
    }

    /** The envelope still carries the intake payload; the turn itself only works on {@link DubheDialogueInput}. */
    private static DialogueDeliveryPayload deliveryPayload() {
        return new DialogueDeliveryPayload(
                "session", "request", "player", "turn", "暮色森林怎么进？", "",
                List.of(), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player"),
                0L, Long.MAX_VALUE
        );
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, String what) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10L);
        }
        fail("timed out waiting for " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Counts the deliveries the player would see; a second one would show up here. */
    private static final class WiringEchoTool implements TianshuTool<JsonObject, JsonObject> {
        private final ToolId id;

        private WiringEchoTool(ToolId id) {
            this.id = id;
        }

        @Override
        public ToolId id() {
            return id;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Echo; a test double.";
        }

        @Override
        public java.lang.reflect.Type argsType() {
            return JsonObject.class;
        }

        @Override
        public java.lang.reflect.Type resultType() {
            return JsonObject.class;
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            JsonObject result = new JsonObject();
            result.add("args", args);
            return CompletableFuture.completedFuture(result);
        }
    }

    private static final class PointOfDeliverySink implements DubheChatOutputSink {
        private final AtomicInteger completed = new AtomicInteger();
        private final List<String> delivered = new CopyOnWriteArrayList<>();

        @Override
        public void append(DubheOutputContext context, String text) {
            delivered.add(text == null ? "" : text);
        }

        @Override
        public void complete(DubheOutputContext context, String fullText) {
            completed.incrementAndGet();
            delivered.add(fullText == null ? "" : fullText);
        }
    }

    /**
     * A real runtime with two seams intercepted: LLM request submission (so a provider failure can be
     * injected) and session control (so releases can be counted without an IA provider).
     */
    private static final class TestRuntime implements ModuleRuntimeAccess {
        private final ProtocolRuntime delegate = new ProtocolRuntime(Runnable::run);
        private final AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        private final AtomicReference<ProtocolTaskSpec> lastSpec = new AtomicReference<>();
        private final List<DialogueSessionControlPayload> sessionControls = new CopyOnWriteArrayList<>();
        private final boolean failLlmSubmit;

        private TestRuntime(boolean failLlmSubmit) {
            this.failLlmSubmit = failLlmSubmit;
        }

        private void registerLlmSink() {
            AdapterDefaults defaults = AdapterDefaults.standard();
            delegate.registerModule(new ModuleDescriptor(
                    "module.llm.test",
                    List.of(new CapabilityDescriptor(
                            "LLM.REQUEST",
                            PayloadType.of("LLM_PROMPT_REQUEST"),
                            LLMPromptRequestPayload.class,
                            BrokerType.BOUNDED_QUEUE,
                            EnumSet.of(PacketType.REQUEST),
                            Priority.LOW,
                            CompletionPolicy.MANUAL_COMPLETE
                    )),
                    defaults.threadPolicy(),
                    defaults.cancellationScope(),
                    defaults.failurePolicy(),
                    defaults.deliveryPolicy(),
                    defaults.cancellable(),
                    defaults.supportsStreaming(),
                    defaults.maxConcurrency(),
                    defaults.queueCapacity()
            ), (envelope, context) -> llmRequest.set(envelope));
        }

        @Override
        public void submit(TianshuEnvelope envelope) {
            if (envelope.payload() instanceof LLMPromptRequestPayload) {
                if (failLlmSubmit) {
                    throw new IllegalStateException("llm provider unavailable");
                }
                delegate.submit(envelope);
                return;
            }
            if (envelope.payload() instanceof DialogueSessionControlPayload control) {
                sessionControls.add(control);
                return;
            }
            delegate.submit(envelope);
        }

        @Override
        public void registerModule(ModuleDescriptor descriptor, EnvelopeHandler handler) {
            delegate.registerModule(descriptor, handler);
        }

        @Override
        public void registerTopic(TopicDescriptor descriptor) {
            delegate.registerTopic(descriptor);
        }

        @Override
        public void registerResponseHandler(
                String requestEnvelopeId,
                ModuleDescriptor descriptor,
                CapabilityDescriptor capabilityDescriptor,
                EnvelopeHandler handler) {
            delegate.registerResponseHandler(requestEnvelopeId, descriptor, capabilityDescriptor, handler);
        }

        @Override
        public void unregisterResponseHandlers(String requestEnvelopeId) {
            delegate.unregisterResponseHandlers(requestEnvelopeId);
        }

        @Override
        public void subscribeTopic(
                ModuleDescriptor moduleDescriptor,
                TopicSubscriptionDescriptor descriptor,
                EnvelopeHandler handler) {
            delegate.subscribeTopic(moduleDescriptor, descriptor, handler);
        }

        @Override
        public TriggerAccess triggers() {
            return delegate.triggers();
        }

        @Override
        public ProtocolTaskHandle submit(ProtocolTaskSpec spec, Runnable task) {
            lastSpec.set(spec);
            return delegate.submit(spec, task);
        }

        @Override
        public <T> ProtocolTaskHandle submit(ProtocolTaskSpec spec, Callable<T> task) {
            return delegate.submit(spec, task);
        }

        @Override
        public ProtocolTaskHandle schedule(ProtocolTaskSpec spec, Runnable task, Duration delay) {
            return delegate.schedule(spec, task, delay);
        }

        @Override
        public int topicSubscriberCount(String topicId) {
            return delegate.topicSubscriberCount(topicId);
        }

        @Override
        public List<ProtocolCapabilityRegistration> capabilityRegistrations(String capabilityId) {
            return delegate.capabilityRegistrations(capabilityId);
        }
    }
}
