package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.rheinmetal.tianshu.presence.PresenceProtocolAdapter;

import com.rheinmetal.tianshu.presence.protocol.PresenceTopics;

import com.rheinmetal.tianshu.presence.protocol.PresencePayloadTypes;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheMemoryWindowPolicy;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmClient;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmPrimitiveClient;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySystem;
import com.rheinmetal.tianshu.dubhe.module.memory.preference.DubhePlayerPreference;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSystem;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurnBatch;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.presence.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一段式压缩编排：单元压缩只写摘要/事件/偏好，<b>不动原文、不动摘要块、不动 prompt</b>。
 *
 * <p>换代（唯一移出 raw 的时刻）在 {@link DubheMemorySummarySwapTest} 里验证。</p>
 */
class DubheMemoryMaintenanceServiceTest {
    private static final String COMPACT_JSON = """
            {
              "schemaVersion": 2,
              "summary": "玩家在调试资源重载日志，并关注缓存和模块加载。",
              "eventChain": [
                { "event": "玩家关注资源重载后的缓存和模块加载。", "importance": 6, "turnRefs": [1] }
              ],
              "preferences": [
                { "fact": "玩家关注缓存与模块加载", "confidence": 0.8, "turnRefs": [2] }
              ]
            }
            """;

    private static final String EVENT_ONLY_JSON = """
            {
              "schemaVersion": 2,
              "eventChain": [
                { "event": "玩家关注资源重载后的缓存和模块加载。", "importance": 6, "turnRefs": [1] }
              ]
            }
            """;

    // Windows: the maintenance service can still hold a handle for a moment after close(), and JUnit's automatic
    // cleanup then fails the test for a directory it could not delete — a red that says nothing about the
    // assertions. The directory is left to the OS instead.
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tempDir;

    @Test
    void singlePassCompactionWritesSummaryEventsAndPlayerPreferences() throws Exception {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        try {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            DubheScope scope = scope();
            DubheStorageLayout layout = layout();
            DubheMemorySystem memorySystem = new DubheMemorySystem(layout, jsonStore(), policy());
            DubheRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            DubheRawTurnBatch batch = select(memorySystem, recentDialogueSystem, scope);
            assertEquals(4, batch.turns().size(), "单元 = 最旧的两轮对话；保持窗口那一组不动");
            RecordingLlmModule llm = new RecordingLlmModule(COMPACT_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            DubheMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> memorySystem.stmBlocks().loadAll(scope).size() == 1
                    && memorySystem.events().loadAll(scope).size() == 1
                    && memorySystem.preferences().loadAll(scope.sharedUserId()).size() == 1);

            assertEquals(1, llm.requests.size(), "一次调用产出三要素");
            LLMPromptRequestPayload request = llm.requests.get(0);
            assertEquals(4096, request.maxTokens().intValue());
            assertEquals(0.2F, request.sampling().temperature(), 1e-6F);
            assertFalse(request.captureThinkingContent());
            List<LLMPromptRequestPayload.MessageItemPayload> messages = request.messages();
            assertEquals(3, messages.size(), "system + raw 轮次 + 指令后缀");
            assertEquals("system", messages.get(0).role());
            assertTrue(messages.get(0).content().contains("schemaVersion"));
            assertTrue(messages.get(1).content().contains("[1] user:"), messages.get(1).content());
            assertTrue(messages.get(2).content().contains("4"), messages.get(2).content());

            DubheStmBlock stm = memorySystem.stmBlocks().loadAll(scope).get(0);
            assertEquals("玩家在调试资源重载日志，并关注缓存和模块加载。", stm.content());
            assertEquals(batch.stmId(), stm.id());
            assertEquals(batch.turns().get(batch.turns().size() - 1).id(), stm.sourceToTurnId(),
                    "单元必须记下覆盖到的最后一轮，换代按它裁剪");

            var event = memorySystem.events().loadAll(scope).get(0);
            assertEquals("玩家关注资源重载后的缓存和模块加载。", event.fact());
            assertEquals(6, event.importance());
            assertEquals(List.of(batch.turns().get(0).id()), event.turnRefs());
            assertEquals(batch.stmId(), event.stmId());
            assertEquals("compact_event", event.sourceKind());

            List<DubhePlayerPreference> preferences = memorySystem.preferences().loadAll(scope.sharedUserId());
            assertEquals(1, preferences.size());
            assertEquals("玩家关注缓存与模块加载", preferences.get(0).fact());
            assertEquals(0.8D, preferences.get(0).confidence(), 1e-9);
            assertTrue(Files.isRegularFile(layout.playerPreferencesFile(scope.sharedUserId())));

            await(() -> activities.size() >= 2);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    activities.stream().map(PresenceActivityPayload::action).toList());
            assertEquals(List.of(PresenceActivityType.PROCESSING_TASK, PresenceActivityType.PROCESSING_TASK),
                    activities.stream().map(PresenceActivityPayload::activityType).toList());
            assertEquals("dubhe.memory.maintenance.world", activities.get(0).activityId());
            assertEquals(activities.get(0).activityId(), activities.get(1).activityId(), "开合必须挂在同一个活动上");
        } finally {
            runtime.close();
        }
    }

    @Test
    void unitCompactionLeavesRawWindowAndSummaryBlockUnchanged() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            DubheScope scope = scope();
            DubheStorageLayout layout = layout();
            DubheMemorySystem memorySystem = new DubheMemorySystem(layout, jsonStore(), policy());
            DubheRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            List<DubheRawTurn> keepBefore = recentDialogueSystem.snapshot(scope).turns();
            RecordingLlmModule llm = new RecordingLlmModule(COMPACT_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            DubheMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> memorySystem.stmBlocks().loadAll(scope).size() == 1);

            assertEquals(rawBefore, recentDialogueSystem.rawTokenCount(scope), "单元压缩不得移出任何原文");
            assertEquals(keepBefore, recentDialogueSystem.snapshot(scope).turns(), "保持窗口逐字节不变");
            assertTrue(memorySystem.summaryBlock(scope).isEmpty(), "没换代就不进摘要块");
            assertEquals(0, recentDialogueSystem.generation(scope));
            assertTrue(memorySystem.summaries().loadGenerations(scope).isEmpty());
            assertTrue(select(memorySystem, recentDialogueSystem, scope).isEmpty(),
                    "覆盖游标之后的原文不够一个单元：不重复压缩同一段");
        } finally {
            runtime.close();
        }
    }

    @Test
    void missingSummaryStillStoresEventsAndDropsOnlyThatUnit() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            DubheScope scope = scope();
            DubheStorageLayout layout = layout();
            DubheMemorySystem memorySystem = new DubheMemorySystem(layout, jsonStore(), policy());
            DubheRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            DubheRawTurnBatch batch = select(memorySystem, recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            RecordingLlmModule llm = new RecordingLlmModule(EVENT_ONLY_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            DubheMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> memorySystem.events().loadAll(scope).size() == 1);

            assertTrue(memorySystem.stmBlocks().loadAll(scope).isEmpty(), "摘要坏了就不写单元块");
            assertEquals(batch.stmId(), memorySystem.events().loadAll(scope).get(0).stmId());
            assertEquals(rawBefore - batch.tokenCount(), recentDialogueSystem.rawTokenCount(scope),
                    "没有摘要就没有覆盖游标，这段原文必须当场移出，否则会被反复压缩");
            assertTrue(memorySystem.preferences().loadAll(scope.sharedUserId()).isEmpty());
        } finally {
            runtime.close();
        }
    }

    @Test
    void failedEventWriteLeavesTheWindowAvailableForRetry() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            DubheScope scope = scope();
            DubheStorageLayout layout = layout();
            DubheMemorySystem memorySystem = new DubheMemorySystem(layout, jsonStore(), policy());
            DubheRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            Files.createDirectories(layout.eventsFile(scope));
            RecordingLlmModule llm = new RecordingLlmModule(COMPACT_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            DubheMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> llm.requests.size() == 1);
            Thread.sleep(100L);

            assertTrue(memorySystem.events().loadAll(scope).isEmpty());
            assertEquals(rawBefore, recentDialogueSystem.rawTokenCount(scope), "写事件失败不能动原文");
        } finally {
            runtime.close();
        }
    }

    @Test
    void stoppedMaintenanceIgnoresLateResultAndLeavesAllStateAlone() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            DubheScope scope = scope();
            DubheStorageLayout layout = layout();
            DubheMemorySystem memorySystem = new DubheMemorySystem(layout, jsonStore(), policy());
            DubheRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            AtomicReference<TianshuEnvelope> heldRequest = new AtomicReference<>();
            registerHeldLlm(runtime, heldRequest);
            registerPrimitive(runtime);
            DubheMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> heldRequest.get() != null);
            service.stop();
            LLMPromptRequestPayload payload = (LLMPromptRequestPayload) heldRequest.get().payload();
            runtime.submit(EnvelopeBuilder.responseTo(
                    "module.llm.memory-test",
                    heldRequest.get(),
                    PayloadType.of("LLM_PROMPT_RESULT"),
                    LLMPromptResultPayload.completed(payload.requestId(), COMPACT_JSON)
            ).build());

            Thread.sleep(100L);
            assertTrue(memorySystem.stmBlocks().loadAll(scope).isEmpty());
            assertTrue(memorySystem.events().loadAll(scope).isEmpty());
            assertEquals(rawBefore, recentDialogueSystem.rawTokenCount(scope));
        } finally {
            runtime.close();
        }
    }

    private DubheRawTurnBatch select(DubheMemorySystem memorySystem, DubheRecentDialogueSystem recentDialogueSystem, DubheScope scope) {
        return recentDialogueSystem.selectCompressionBatch(scope, memorySystem.stmCoveredThroughTurnId(scope));
    }

    private DubheScope scope() {
        return new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);
    }

    private DubheStorageLayout layout() {
        return new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private DubheJsonStore jsonStore() {
        return new DubheJsonStore(new FakeGameEnvironment());
    }

    /**
     * 维护测试用的窗口策略（32K 档）。
     *
     * <p>KEEP_MAX = 3277、单元 = 8192、MAX_RAW = 19990。测试数据是"三组 4000 token 的旧对话 +
     * 一组 600 token 的最近对话"：保持窗口只装得下最后一组，可压部分 12000 够一个完整单元，
     * 总窗口 12600 还没到换代容量，所以这段流程只做单元压缩。</p>
     */
    private static DubheMemoryWindowPolicy policy() {
        return DubheMemoryWindowPolicy.fromBudget(32_768, 3, 60000L);
    }

    private static DubheRecentDialogueSystem recentDialogue() {
        return new DubheRecentDialogueSystem(policy(), (requestId, role, content) -> OptionalInt.empty());
    }

    private void appendWindow(DubheRecentDialogueSystem recentDialogueSystem, DubheScope scope) {
        appendRound(recentDialogueSystem, scope, "turn-1", 2_000);
        appendRound(recentDialogueSystem, scope, "turn-2", 2_000);
        appendRound(recentDialogueSystem, scope, "turn-3", 2_000);
        appendRound(recentDialogueSystem, scope, "turn-4", 300);
    }

    private void appendRound(DubheRecentDialogueSystem recentDialogueSystem, DubheScope scope, String turnId, int tokens) {
        recentDialogueSystem.append(scope, turn(scope, "user", "第" + turnId + "问。", turnId, tokens));
        recentDialogueSystem.append(scope, turn(scope, "assistant", "第" + turnId + "答。", turnId, tokens));
    }

    private DubheRawTurn turn(DubheScope scope, String role, String content, String turnId, int tokens) {
        return DubheRawTurn.dialogue(scope, role, content, "session", turnId).withTokenCount(tokens);
    }

    private DubheMemoryMaintenanceService service(
            ProtocolRuntime runtime,
            DubheStorageLayout layout,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem
    ) {
        DubheProtocolAdapter adapter = new DubheProtocolAdapter(runtime);
        return new DubheMemoryMaintenanceService(
                adapter,
                memorySystem,
                recentDialogueSystem,
                new DubheLlmClient(adapter),
                new DubheLlmPrimitiveClient(adapter, 2_000L),
                new DubheMemoryTaskPromptRepository(layout, DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN))
        );
    }

    private static void registerLlm(ProtocolRuntime runtime, RecordingLlmModule llm) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.memory-test",
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
        ), llm::handle);
    }

    /**
     * presence 活动观测点：单并发收单。
     *
     * <p>本用例断言的是"活动开→合"（STARTED 在前、ENDED 在后，且挂在同一个 activityId 上），顺序本身
     * 就是契约；而 {@code AdapterDefaults.standard()} 的 maxConcurrency 是 CPU 数，
     * {@code AbstractQueueBroker.drain} 会把这几条事件并行派发，落进列表的先后就不等于发布先后。
     * 钉成单并发后顺序重新可断言（与 {@code AsrPresenceActivityTest} 同一个做法）。</p>
     */
    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        // Presence owns its topics now; the kernel no longer registers them.
        new PresenceProtocolAdapter(runtime).registerOwnedTopics(runtime);
        List<PresenceActivityPayload> activities = java.util.Collections.synchronizedList(new ArrayList<>());
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.memory-test",
                        List.of(),
                        defaults.threadPolicy(),
                        defaults.cancellationScope(),
                        defaults.failurePolicy(),
                        defaults.deliveryPolicy(),
                        defaults.cancellable(),
                        defaults.supportsStreaming(),
                        1,
                        defaults.queueCapacity()
                ),
                new TopicSubscriptionDescriptor(
                        PresenceTopics.ACTIVITY,
                        PresencePayloadTypes.ACTIVITY,
                        PresenceActivityPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                (envelope, context) -> activities.add((PresenceActivityPayload) envelope.payload())
        );
        return activities;
    }

    private static void registerHeldLlm(ProtocolRuntime runtime, AtomicReference<TianshuEnvelope> request) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.memory-test",
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
        ), (envelope, context) -> request.set(envelope));
    }

    private static void registerPrimitive(ProtocolRuntime runtime) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.primitive.memory-test",
                List.of(new CapabilityDescriptor(
                        "LLM.PRIMITIVE_QUERY",
                        PayloadType.of("LLM_PRIMITIVE_QUERY"),
                        LLMPrimitiveQueryPayload.class,
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
        ), (envelope, context) -> {
            LLMPrimitiveQueryPayload payload = (LLMPrimitiveQueryPayload) envelope.payload();
            LLMPrimitiveResultPayload result = LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT.equals(payload.queryType())
                    ? LLMPrimitiveResultPayload.tokenCount(payload.requestId(), 12)
                    : LLMPrimitiveResultPayload.runtime(payload.requestId(), LLMRuntimeSnapshotPayload.unavailable());
            context.submit(EnvelopeBuilder.responseTo(
                    "module.llm.primitive.memory-test",
                    envelope,
                    PayloadType.of("LLM_PRIMITIVE_RESULT"),
                    result
            ).build());
            context.complete(envelope.envelopeId());
        });
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class RecordingLlmModule {
        private final List<LLMPromptRequestPayload> requests = new ArrayList<>();
        private final String response;

        RecordingLlmModule(String response) {
            this.response = response == null ? "" : response;
        }

        void handle(TianshuEnvelope envelope, ProtocolContext context) {
            LLMPromptRequestPayload payload = (LLMPromptRequestPayload) envelope.payload();
            requests.add(payload);
            context.submit(EnvelopeBuilder.responseTo(
                    "module.llm.memory-test",
                    envelope,
                    PayloadType.of("LLM_PROMPT_RESULT"),
                    LLMPromptResultPayload.completed(payload.requestId(), response)
            ).build());
            context.complete(envelope.envelopeId());
        }
    }
}
