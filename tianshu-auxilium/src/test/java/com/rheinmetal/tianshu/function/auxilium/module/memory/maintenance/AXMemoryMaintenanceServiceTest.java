package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.preference.AXPlayerPreference;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
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
 * <p>换代（唯一移出 raw 的时刻）在 {@link AXMemorySummarySwapTest} 里验证。</p>
 */
class AXMemoryMaintenanceServiceTest {
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

    @TempDir
    Path tempDir;

    @Test
    void singlePassCompactionWritesSummaryEventsAndPlayerPreferences() throws Exception {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        try {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            AXScope scope = scope();
            AXStorageLayout layout = layout();
            AXMemorySystem memorySystem = new AXMemorySystem(layout, jsonStore(), policy());
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            AXRawTurnBatch batch = select(memorySystem, recentDialogueSystem, scope);
            assertEquals(4, batch.turns().size(), "单元 = 最旧的两轮对话；保持窗口那一组不动");
            RecordingLlmModule llm = new RecordingLlmModule(COMPACT_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> memorySystem.stmBlocks().loadAll(scope).size() == 1
                    && memorySystem.events().loadAll(scope).size() == 1
                    && memorySystem.preferences().loadAll(scope.sharedUserId()).size() == 1);

            assertEquals(1, llm.requests.size(), "一次调用产出三要素");
            LLMPromptRequestPayload request = llm.requests.get(0);
            assertEquals("TASK", request.lane());
            assertEquals(4096, request.maxTokens().intValue());
            assertEquals(0.2F, request.temperature().floatValue(), 1e-6F);
            assertTrue(request.thinking());
            assertFalse(request.captureThinkingContent());
            List<LLMPromptRequestPayload.MessageItemPayload> messages = request.chunks().get(0).messageContent();
            assertEquals(3, messages.size(), "system + raw 轮次 + 指令后缀");
            assertEquals("system", messages.get(0).role());
            assertTrue(messages.get(0).content().contains("schemaVersion"));
            assertTrue(messages.get(1).content().contains("[1] user:"), messages.get(1).content());
            assertTrue(messages.get(2).content().contains("4"), messages.get(2).content());

            AXStmBlock stm = memorySystem.stmBlocks().loadAll(scope).get(0);
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

            List<AXPlayerPreference> preferences = memorySystem.preferences().loadAll(scope.sharedUserId());
            assertEquals(1, preferences.size());
            assertEquals("玩家关注缓存与模块加载", preferences.get(0).fact());
            assertEquals(0.8D, preferences.get(0).confidence(), 1e-9);
            assertTrue(Files.isRegularFile(layout.playerPreferencesFile(scope.sharedUserId())));

            await(() -> activities.size() >= 2);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    activities.stream().map(PresenceActivityPayload::action).toList());
            assertEquals(List.of(PresenceActivityType.PROCESSING_TASK, PresenceActivityType.PROCESSING_TASK),
                    activities.stream().map(PresenceActivityPayload::activityType).toList());
            assertEquals("ax.memory.maintenance.world", activities.get(0).activityId());
            assertEquals(activities.get(0).activityId(), activities.get(1).activityId(), "开合必须挂在同一个活动上");
        } finally {
            runtime.close();
        }
    }

    @Test
    void unitCompactionLeavesRawWindowAndSummaryBlockUnchanged() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            AXScope scope = scope();
            AXStorageLayout layout = layout();
            AXMemorySystem memorySystem = new AXMemorySystem(layout, jsonStore(), policy());
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            List<AXRawTurn> keepBefore = recentDialogueSystem.snapshot(scope).turns();
            RecordingLlmModule llm = new RecordingLlmModule(COMPACT_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

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
            AXScope scope = scope();
            AXStorageLayout layout = layout();
            AXMemorySystem memorySystem = new AXMemorySystem(layout, jsonStore(), policy());
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            AXRawTurnBatch batch = select(memorySystem, recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            RecordingLlmModule llm = new RecordingLlmModule(EVENT_ONLY_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

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
            AXScope scope = scope();
            AXStorageLayout layout = layout();
            AXMemorySystem memorySystem = new AXMemorySystem(layout, jsonStore(), policy());
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            Files.createDirectories(layout.eventsFile(scope));
            RecordingLlmModule llm = new RecordingLlmModule(COMPACT_JSON);
            registerLlm(runtime, llm);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

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
            AXScope scope = scope();
            AXStorageLayout layout = layout();
            AXMemorySystem memorySystem = new AXMemorySystem(layout, jsonStore(), policy());
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue();
            appendWindow(recentDialogueSystem, scope);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            AtomicReference<TianshuEnvelope> heldRequest = new AtomicReference<>();
            registerHeldLlm(runtime, heldRequest);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, layout, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> heldRequest.get() != null);
            service.stop();
            LLMPromptRequestPayload payload = (LLMPromptRequestPayload) heldRequest.get().payload();
            runtime.submit(EnvelopeBuilder.responseTo(
                    "module.llm.memory-test",
                    heldRequest.get(),
                    PayloadType.LLM_PROMPT_RESULT,
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

    private AXRawTurnBatch select(AXMemorySystem memorySystem, AXRecentDialogueSystem recentDialogueSystem, AXScope scope) {
        return recentDialogueSystem.selectCompressionBatch(scope, memorySystem.stmCoveredThroughTurnId(scope));
    }

    private AXScope scope() {
        return new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
    }

    private AXStorageLayout layout() {
        return new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private AXJsonStore jsonStore() {
        return new AXJsonStore(new FakeGameEnvironment());
    }

    /**
     * 维护测试用的窗口策略（32K 档）。
     *
     * <p>KEEP_MAX = 3277、单元 = 8192、MAX_RAW = 19990。测试数据是"三组 4000 token 的旧对话 +
     * 一组 600 token 的最近对话"：保持窗口只装得下最后一组，可压部分 12000 够一个完整单元，
     * 总窗口 12600 还没到换代容量，所以这段流程只做单元压缩。</p>
     */
    private static AXMemoryWindowPolicy policy() {
        return AXMemoryWindowPolicy.fromBudget(32_768, 3, 60000L);
    }

    private static AXRecentDialogueSystem recentDialogue() {
        return new AXRecentDialogueSystem(policy(), (requestId, role, content) -> OptionalInt.empty());
    }

    private void appendWindow(AXRecentDialogueSystem recentDialogueSystem, AXScope scope) {
        appendRound(recentDialogueSystem, scope, "turn-1", 2_000);
        appendRound(recentDialogueSystem, scope, "turn-2", 2_000);
        appendRound(recentDialogueSystem, scope, "turn-3", 2_000);
        appendRound(recentDialogueSystem, scope, "turn-4", 300);
    }

    private void appendRound(AXRecentDialogueSystem recentDialogueSystem, AXScope scope, String turnId, int tokens) {
        recentDialogueSystem.append(scope, turn(scope, "user", "第" + turnId + "问。", turnId, tokens));
        recentDialogueSystem.append(scope, turn(scope, "assistant", "第" + turnId + "答。", turnId, tokens));
    }

    private AXRawTurn turn(AXScope scope, String role, String content, String turnId, int tokens) {
        return AXRawTurn.dialogue(scope, role, content, "session", turnId).withTokenCount(tokens);
    }

    private AXMemoryMaintenanceService service(
            ProtocolRuntime runtime,
            AXStorageLayout layout,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem
    ) {
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        return new AXMemoryMaintenanceService(
                adapter,
                memorySystem,
                recentDialogueSystem,
                new AXLlmClient(adapter),
                new AXLlmPrimitiveClient(adapter, 2_000L),
                new AXMemoryTaskPromptRepository(layout, AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN))
        );
    }

    private static void registerLlm(ProtocolRuntime runtime, RecordingLlmModule llm) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.memory-test",
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
                        com.rheinmetal.tianshu.protocol.ProtocolTopics.PRESENCE_ACTIVITY,
                        PayloadType.PRESENCE_ACTIVITY,
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
        ), (envelope, context) -> request.set(envelope));
    }

    private static void registerPrimitive(ProtocolRuntime runtime) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.primitive.memory-test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                        PayloadType.LLM_PRIMITIVE_QUERY,
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
                    PayloadType.LLM_PRIMITIVE_RESULT,
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
                    PayloadType.LLM_PROMPT_RESULT,
                    LLMPromptResultPayload.completed(payload.requestId(), response)
            ).build());
            context.complete(envelope.envelopeId());
        }
    }
}
