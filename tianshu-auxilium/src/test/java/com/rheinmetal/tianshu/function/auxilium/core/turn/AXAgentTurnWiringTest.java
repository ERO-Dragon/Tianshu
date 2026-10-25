package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.auxilium.AXParticipantRegistrar;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXAgentLoop;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunOutcome;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDelivery;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateEventLedger;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateRegistry;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateService;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputContext;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXReplyTextRepository;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
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
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerAccess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
 * Production wiring of {@link AXAgentLoop} into the main dialogue path (design §12.7, §12.8).
 *
 * <p>These are the acceptance cases: request equivalence, terminal equivalence, exactly-once release,
 * cancellation (no request before it, event wake-up after it), the 10s loop deadline and a failing
 * submit. Every case drives the turn the way production does: {@code startTurn} returns immediately and
 * the run itself happens on the submitter's own thread.</p>
 */
class AXAgentTurnWiringTest {

    private static final AXDialogueInputMapper MAPPER = new AXDialogueInputMapper();
    private static final AXInputNormalizer NORMALIZER = new AXInputNormalizer();
    private static final AXReplyTextRepository REPLY_TEXTS =
            new AXReplyTextRepository(AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN));

    private static final ExecutorService RUNS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ax-agent-wiring-test");
        thread.setDaemon(true);
        return thread;
    });

    @Test
    void mainDialogueRunsThroughTheLoopAndSubmitsTheSameChatRequestWithoutTools() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXScope scope = writableScope();
        DialogueDeliveryPayload delivery = delivery();
        AXRecentDialogueSystem recent = emptyRecent();
        AXRequest request = AXRequest.fromNormalizedInput(NORMALIZER.normalize(MAPPER.map(delivery)));
        // The run builds its prompt before the user turn is appended, so the reference context and
        // payload are taken from the same still-empty window instead of from the window after startTurn.
        LLMPromptRequestPayload expected = new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null))
                .buildChatRequest(
                        request,
                        new AXContextCollector(null, recent).collect(scope, request, List.of()),
                        AXContextBudget.DEFAULT)
                .withDialogueAuthorization(
                        delivery.sessionId(), AXModule.MODULE_ID, AXParticipantRegistrar.PARTICIPANT_ID, delivery.turnId());
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, recent, scope, null, (taskId, task) -> RUNS.execute(task));

        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request to leave the run");

        LLMPromptRequestPayload actual = (LLMPromptRequestPayload) runtime.llmRequest.get().payload();
        assertEquals(expected, actual, "the loop must not change a single field of the prompt request");
        assertTrue(actual.toolsJson().isEmpty(), "main dialogue declares no tools (A3)");
        assertEquals("CHAT", actual.lane(), "the envelope stays on the CHAT lane; only our wait moves (§12.1)");
    }

    @Test
    void completedTurnCompletesTheAssistantOnceAndReleasesOnce() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXScope scope = writableScope();
        AXRecentDialogueSystem recent = emptyRecent();
        PointOfDeliverySink sink = new PointOfDeliverySink();
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, recent, scope, sink, (taskId, task) -> RUNS.execute(task));

        DialogueDeliveryPayload delivery = delivery();
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
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        List<Runnable> gated = new ArrayList<>();
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> gated.add(task));

        DialogueDeliveryPayload delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> gated.size() == 1, 5_000L, "the run to be handed to the submitter");

        orchestrator.interruptActiveTurn(AXTurnCancellation.playerInterrupted("player spoke again"));
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
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> RUNS.execute(task));

        DialogueDeliveryPayload delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request");

        orchestrator.interruptActiveTurn(AXTurnCancellation.playerInterrupted("player spoke again"));
        await(() -> !runtime.sessionControls.isEmpty(), 5_000L, "the cancellation release");
        assertEquals(DialogueReleaseReason.PLAYER_CANCELLED, runtime.sessionControls.get(0).reason());

        // Longer than one poll slice: a run woken by the 50ms fallback would already have counted itself.
        sleep(300L);
        assertEquals(0, orchestrator.pollRescuedTurns(),
                "cancelRequestAwaitTerminal must wake the run through its callback (event path)");
    }

    @Test
    void loopDeadlineRetiresTheTurnAtTenSecondsInsteadOfTheEnvelopeExpiry() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> RUNS.execute(task));

        long startedAt = System.currentTimeMillis();
        DialogueDeliveryPayload delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);
        await(() -> runtime.llmRequest.get() != null, 5_000L, "the CHAT request");

        // No answer is ever delivered: only the loop's own deadline can retire this turn.
        await(() -> !runtime.sessionControls.isEmpty(), 15_000L, "the loop deadline to expire the turn");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals(DialogueReleaseReason.EXPIRED, runtime.sessionControls.get(0).reason());
        assertTrue(elapsed >= AXRunPolicy.SIMPLE_TIMEOUT_MILLIS - 500L,
                "the fallback must not fire before the loop budget is spent");
        assertTrue(elapsed < 15_000L, "the turn retires at the loop deadline, not at the 60s envelope expiry");
        assertEquals(0, orchestrator.pollRescuedTurns(), "a deadline is not a poll rescue");
        assertEquals(1, runtime.sessionControls.size());
    }

    @Test
    void submitFailureReleasesOwnerFailedInsteadOfHangingTheTurn() {
        TestRuntime runtime = new TestRuntime(true);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null, (taskId, task) -> RUNS.execute(task));

        DialogueDeliveryPayload delivery = delivery();
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
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, emptyRecent(), writableScope(), null,
                (taskId, task) -> {
                    throw new RejectedExecutionException("lane saturated");
                });

        DialogueDeliveryPayload delivery = delivery();
        orchestrator.startTurn(deliveryEnvelope(delivery), delivery);

        assertEquals(1, runtime.sessionControls.size(), "a rejected run retires the turn immediately");
        assertEquals(DialogueReleaseReason.OWNER_FAILED, runtime.sessionControls.get(0).reason());
        assertNull(runtime.llmRequest.get());
    }

    @Test
    void delegateReplyIsNeverSpokenAndTheTurnEndsImmediately() {
        TestRuntime runtime = new TestRuntime(false);
        runtime.registerLlmSink();
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXScope scope = writableScope();
        AXRecentDialogueSystem recent = emptyRecent();
        List<Runnable> delegateRuns = new ArrayList<>();
        List<AXDelegateDelivery> deliveries = new CopyOnWriteArrayList<>();
        AXDelegateRegistry delegateRegistry = new AXDelegateRegistry();
        AXDelegateService delegateService = new AXDelegateService(
                (payload, handler) -> {
                },
                delegateRegistry,
                null,
                null,
                new AXDelegateEventLedger(),
                null,
                (taskId, task) -> delegateRuns.add(task),
                deliveries::add,
                LogSink.NOOP,
                System::currentTimeMillis);
        PointOfDeliverySink sink = new PointOfDeliverySink();
        AXTurnOrchestrator orchestrator = orchestrator(
                adapter, llmClient, recent, scope, sink,
                (taskId, task) -> RUNS.execute(task), delegateService);
        String spokenSentence = "这个问题让我去查一下。";

        DialogueDeliveryPayload delivery = delivery();
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
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);

        adapter.submitDelegateRun("ax.delegate.test-1", () -> {
        });

        ProtocolTaskSpec spec = runtime.lastSpec.get();
        assertNotNull(spec);
        assertEquals(ExecutionLane.LONG, spec.lane(), "委派等待跑在 LONG 车道，不占协议 CPU 车道");
        assertEquals(AXProtocolAdapter.MODULE_ID + ":agent_delegate", spec.concurrencyKey(),
                "独立并发键：一次 120s 检索不得堵住主对话的 :agent");
        assertEquals(1, spec.maxConcurrency());
        assertEquals(3, spec.queueCapacity());
    }

    @Test
    void runFailureKeepsTheOriginalThrowable() {
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.simple());
        IllegalStateException boom = new IllegalStateException("llm provider unavailable");

        AXRunOutcome<String> outcome = loop.run(context -> {
            throw boom;
        }, AXRunCancellation.none());

        assertEquals(AXRunTermination.FAILED, outcome.termination());
        assertSame(boom, outcome.failure(), "the failure must not be wrapped or replaced (§12.5)");
        assertNotNull(outcome.failure());
    }

    // ---------------------------------------------------------------- fixtures

    private static AXTurnOrchestrator orchestrator(
            AXProtocolAdapter adapter,
            AXLlmClient llmClient,
            AXRecentDialogueSystem recent,
            AXScope scope,
            AXChatOutputSink sink,
            AXAgentRunSubmitter submitter
    ) {
        return orchestrator(adapter, llmClient, recent, scope, sink, submitter, null);
    }

    private static AXTurnOrchestrator orchestrator(
            AXProtocolAdapter adapter,
            AXLlmClient llmClient,
            AXRecentDialogueSystem recent,
            AXScope scope,
            AXChatOutputSink sink,
            AXAgentRunSubmitter submitter,
            AXDelegateService delegateService
    ) {
        AXOutputProcessor outputProcessor = new AXOutputProcessor(
                adapter, (AuxiliumOutputSettings) () -> AuxiliumOutputMode.UI_ONLY, sink, REPLY_TEXTS);
        return new AXTurnOrchestrator(
                () -> scope,
                MAPPER,
                NORMALIZER,
                null,
                null,
                new AXContextCollector(null, recent),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                null,
                llmClient,
                new AXSessionController(adapter),
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

    private static AXRecentDialogueSystem emptyRecent() {
        return new AXRecentDialogueSystem(AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L));
    }

    private static AXScope writableScope() {
        return new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
    }

    private static DialogueDeliveryPayload delivery() {
        return new DialogueDeliveryPayload(
                "session",
                "request",
                "player",
                "turn",
                "暮色森林怎么进？",
                "",
                List.of(),
                DialogueInteractionHints.empty(),
                DialogueContextSnapshot.empty("player"),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 30_000L
        );
    }

    private static TianshuEnvelope deliveryEnvelope(DialogueDeliveryPayload delivery) {
        return EnvelopeBuilder.commandToCapability(
                IaProtocolAdapter.SOURCE_ID,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_DELIVERY,
                delivery
        ).build();
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
    private static final class PointOfDeliverySink implements AXChatOutputSink {
        private final AtomicInteger completed = new AtomicInteger();
        private final List<String> delivered = new CopyOnWriteArrayList<>();

        @Override
        public void append(AXOutputContext context, String text) {
            delivered.add(text == null ? "" : text);
        }

        @Override
        public void complete(AXOutputContext context, String fullText) {
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
                            ProtocolCapabilities.LLM_REQUEST,
                            PayloadType.LLM_PROMPT_REQUEST,
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
        public VoiceTriggerAccess voiceTriggers() {
            return delegate.voiceTriggers();
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
