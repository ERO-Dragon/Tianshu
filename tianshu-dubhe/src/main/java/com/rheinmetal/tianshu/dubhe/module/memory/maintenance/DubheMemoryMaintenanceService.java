package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmClient;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmPrimitiveClient;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmRagClient;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmRequestHandler;
import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.failure.DubheFailureCause;
import com.rheinmetal.tianshu.dubhe.module.memory.compression.DubheMemoryCompressedEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.compression.DubheMemoryCompressedPreference;
import com.rheinmetal.tianshu.dubhe.module.memory.compression.DubheMemoryCompressionParser;
import com.rheinmetal.tianshu.dubhe.module.memory.compression.DubheMemoryCompressionResult;
import com.rheinmetal.tianshu.dubhe.module.memory.preference.DubhePlayerPreference;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSystem;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySystem;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.DubheMemoryRagProjectionService;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheWorldEventMemoryLinker;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheAttachedWorldEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheEventVector;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurnBatch;

/**
 * 记忆维护编排器。
 *
 * <p>压缩链路是<b>一段式</b>：一次 TASK 车道调用同时产出 {@code summary / eventChain / preferences}
 * 三要素（{@link DubheMemoryCompressionParser}），不再有"先压摘要、再从摘要抽事实"的两次调用。
 * 三要素各自独立落库、字段级降级：摘要坏了事件照落，事件坏了摘要照落，不整体丢弃。</p>
 *
 * <p>请求形态（为前缀复用准备）：{@code system(规则+schema) + raw 轮次 + 指令后缀}。system 是每次
 * 压缩都逐字节相同的前缀，指令恒定是最后一个 user 消息（真后缀）；摘要块槽位由下一片接在 system
 * 之后，本片还没有块可放。事件必须带轮级溯源，因此 raw 轮次每行都带行首序号。</p>
 */
public final class DubheMemoryMaintenanceService {
    private static final int VECTOR_BATCH_SIZE = 64;
    private static final long TASK_STAGE_TIMEOUT_SECONDS = 120L;
    private static final long WORLD_EVENT_ATTACHMENT_GRACE_MILLIS = 30_000L;
    private static final String STATUS_KEY_STARTED = "tianshu.runtime.module.dubhe.memory_maintenance_started";
    private static final String STATUS_KEY_COMPLETE = "tianshu.runtime.module.dubhe.memory_maintenance_complete";
    private static final String STATUS_KEY_FAILED = "tianshu.runtime.module.dubhe.memory_maintenance_failed";

    private volatile MaintenanceObserver maintenanceObserver = MaintenanceObserver.NONE;

    /**
     * 记忆维护的成败对外说一次：失败账本挂在这里。
     *
     * <p>维护是后台工作，玩家看见的只有一条状态；没有这个观察点，"记忆维护因为限流失败了"这件事在
     * 玩家那边就等于什么也没发生——而它恰恰是唯一能靠再点一次救回来的那种失败。</p>
     */
    public interface MaintenanceObserver {
        MaintenanceObserver NONE = new MaintenanceObserver() {
        };

        default void onMaintenanceFailed(DubheFailureCause cause) {
        }

        default void onMaintenanceCompleted() {
        }
    }

    /** 接上成败观察者；传 {@code null} 等于不观察。 */
    public void observeFailures(MaintenanceObserver observer) {
        this.maintenanceObserver = observer == null ? MaintenanceObserver.NONE : observer;
    }

    /** 一段式压缩的输出预算：三要素共用（摘要无长度上限，事件链受它约束）。 */
    private static final int COMPACT_MAX_TOKENS = 4096;
    /** 抽取类任务要稳定复述：低温度。 */
    private static final float COMPACT_TEMPERATURE = 0.2F;

    private static final String SOURCE_KIND_COMPACT_EVENT = "compact_event";

    private final DubheProtocolAdapter adapter;
    private final DubheMemorySystem memorySystem;
    private final DubheRecentDialogueSystem recentDialogueSystem;
    private final DubheLlmClient llmClient;
    private final DubheLlmPrimitiveClient primitiveClient;
    private final DubheMemoryRagProjectionService ragProjectionService;
    private final DubheMemoryTaskPromptRepository promptRepository;
    private final BooleanSupplier thinkingEnhancement;
    private final DubheMemoryCompressionParser compressionParser = new DubheMemoryCompressionParser();
    private final DubheWorldEventMemoryLinker worldEventLinker = new DubheWorldEventMemoryLinker(null);
    private final DubheMemoryDerivedMaintenanceService derivedMaintenanceService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile ProtocolTaskHandle currentTask;

    public DubheMemoryMaintenanceService(
            DubheProtocolAdapter adapter,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheLlmClient llmClient,
            DubheLlmPrimitiveClient primitiveClient
    ) {
        this(adapter, memorySystem, recentDialogueSystem, llmClient, primitiveClient, null, null);
    }

    public DubheMemoryMaintenanceService(
            DubheProtocolAdapter adapter,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheLlmClient llmClient,
            DubheLlmPrimitiveClient primitiveClient,
            DubheMemoryTaskPromptRepository promptRepository
    ) {
        this(adapter, memorySystem, recentDialogueSystem, llmClient, primitiveClient, null, promptRepository);
    }

    public DubheMemoryMaintenanceService(
            DubheProtocolAdapter adapter,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheLlmClient llmClient,
            DubheLlmPrimitiveClient primitiveClient,
            DubheLlmRagClient ragClient,
            DubheMemoryTaskPromptRepository promptRepository
    ) {
        this(adapter, memorySystem, recentDialogueSystem, llmClient, primitiveClient, ragClient, promptRepository,
                () -> false);
    }

    /**
     * @param thinkingEnhancement the player's "spend more thinking for better replies" switch; it
     *                            also lifts compaction from the low tier to the next one
     */
    public DubheMemoryMaintenanceService(
            DubheProtocolAdapter adapter,
            DubheMemorySystem memorySystem,
            DubheRecentDialogueSystem recentDialogueSystem,
            DubheLlmClient llmClient,
            DubheLlmPrimitiveClient primitiveClient,
            DubheLlmRagClient ragClient,
            DubheMemoryTaskPromptRepository promptRepository,
            BooleanSupplier thinkingEnhancement
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.recentDialogueSystem = recentDialogueSystem;
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.primitiveClient = Objects.requireNonNull(primitiveClient, "primitiveClient");
        this.thinkingEnhancement = thinkingEnhancement == null ? () -> false : thinkingEnhancement;
        this.ragProjectionService = ragClient == null ? null : new DubheMemoryRagProjectionService(this.memorySystem, ragClient);
        this.promptRepository = promptRepository == null ? new DubheMemoryTaskPromptRepository(null, null) : promptRepository;
        this.derivedMaintenanceService = new DubheMemoryDerivedMaintenanceService(this.memorySystem);
    }

    public boolean requestMaintenance(DubheScope scope) {
        if (scope == null || !scope.writable() || !running.compareAndSet(false, true)) {
            return false;
        }
        long generation = lifecycleGeneration.incrementAndGet();
        String activityId = "dubhe.memory.maintenance." + DubheStorageSafeName.of(scope.worldId());
        AtomicBoolean activityStarted = new AtomicBoolean(false);
        currentTask = adapter.submitAxTask(activityId, ExecutionLane.LONG, () -> {
            try {
                if (!isCurrent(generation)) {
                    return;
                }
                adapter.publishMaintenanceActivity(activityId, true);
                activityStarted.set(true);
                run(scope, generation);
            } catch (RuntimeException exception) {
                if (isCurrent(generation)) {
                    reportFailure(DubheFailureCause.UNKNOWN);
                    publishFailedStatus();
                }
                throw exception;
            } finally {
                if (activityStarted.get()) {
                    adapter.publishMaintenanceActivity(activityId, false);
                }
                if (isCurrent(generation)) {
                    running.set(false);
                }
            }
        });
        boolean accepted = currentTask != null && currentTask.state() != com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState.REJECTED;
        if (!accepted) {
            running.set(false);
        }
        return accepted;
    }

    public void stop() {
        lifecycleGeneration.incrementAndGet();
        ProtocolTaskHandle task = currentTask;
        if (task != null && !task.isDone()) {
            task.cancel("AX memory maintenance stopped");
        }
        running.set(false);
    }

    private void run(DubheScope scope, long generation) {
        if (!isCurrent(generation)) {
            return;
        }
        memorySystem.ensureStorageManifest(scope);
                // 选批要跳过"已有单元摘要覆盖过"的轮次：这些原文仍留在窗口里（单元压缩不动原文），
        // 只是不再重复压缩。
        String coveredThroughTurnId = memorySystem.stmCoveredThroughTurnId(scope);
        DubheRawTurnBatch batch = recentDialogueSystem == null
                ? DubheRawTurnBatch.empty()
                : recentDialogueSystem.selectCompressionBatch(scope, coveredThroughTurnId);
        AtomicBoolean startedStatusPublished = new AtomicBoolean(false);
        boolean compressed = false;
        boolean swapped = false;
        if (!batch.isEmpty()) {
            String unitId = batch.stmId();
            Optional<DubheStmBlock> existing = existingStmFor(scope, batch);
            if (existing.isPresent() && hasEventsForStm(scope, unitId)) {
                // 这个单元已经完整落库（摘要 + 事件）：不重复调用模型，也<b>不动原文</b>——
                // 原文只在换代时移出窗口。
            } else {
                compressed = compactUnit(scope, batch, existing, startedStatusPublished, generation);
                if (!isCurrent(generation)) {
                    return;
                }
            }
        }
        if (!isCurrent(generation)) {
            return;
        }
        // 换代判定与"这一轮有没有新单元"无关：水位可能在上一次已经越过，只要库里还有待晋升单元
        // 就该换代。放在压缩分支之外，避免一次失败的晋升被永久卡住。
        swapped = maybeSwapGeneration(scope);
        if (!isCurrent(generation)) {
            return;
        }
        VectorRebuildResult vectorResult = rebuildMissingVectors(scope, startedStatusPublished, generation).join();
        if (!isCurrent(generation)) {
            return;
        }
        int rebuiltVectors = vectorResult.rebuiltVectors();
        int projectedEntries = projectMemoryRag(scope, vectorResult.embeddingNamespace(), startedStatusPublished, generation).join();
        if (!isCurrent(generation)) {
            return;
        }
        DubheMemoryDerivedMaintenanceResult derivedResult = derivedMaintenanceService.maintain(scope);
        if (isCurrent(generation)
                && (compressed || swapped || rebuiltVectors > 0 || projectedEntries > 0 || (derivedResult.ran() && derivedResult.stmChainRewritten()))) {
            publishCompleteStatus();
        }
        if (isCurrent(generation)) {
            reportCompleted();
        }
    }

    private void reportFailure(DubheFailureCause cause) {
        MaintenanceObserver current = maintenanceObserver;
        if (current == null) {
            return;
        }
        try {
            current.onMaintenanceFailed(cause == null ? DubheFailureCause.UNKNOWN : cause);
        } catch (RuntimeException ignored) {
            // 记账是本轮旁路：它坏了，维护该发的状态仍然要出去。
        }
    }

    private void reportCompleted() {
        MaintenanceObserver current = maintenanceObserver;
        if (current == null) {
            return;
        }
        try {
            current.onMaintenanceCompleted();
        } catch (RuntimeException ignored) {
            // 同上：观察者不许影响维护本身。
        }
    }

    /**
     * 一个压缩单元的一段式提交。
     *
     * <p>顺序固定：摘要块 → 事件 → 偏好 → 消费窗口。任何一步失败都<b>不消费</b>，原文留在窗口里
     * 等下一次（原文不可篡改）；偏好写入失败除外——此时摘要与事件已经落盘，再消费一次只会重复
     * 写事件，所以偏好失败只丢这一批偏好。</p>
     *
     * @return 是否产出了摘要块
     */
    private boolean compactUnit(
            DubheScope scope,
            DubheRawTurnBatch batch,
            Optional<DubheStmBlock> existing,
            AtomicBoolean startedStatusPublished,
            long generation
    ) {
        publishStartedStatus(startedStatusPublished);
        DubheCompactionOutcome outcome = compact(batch, scope.worldId()).join();
        if (!isCurrent(generation)) {
            return false;
        }
        if (outcome == null || outcome.result() == null || outcome.result().isEmpty()) {
            // 调用失败 / 取消 / 输出不可解析 / 模型什么都没给：没有任何可提交内容，raw 留在窗口。
            return false;
        }
        DubheMemoryCompressionResult compaction = outcome.result();
        String unitId = batch.stmId();
        List<DubheAttachedWorldEvent> attachedWorldEvents = memorySystem.unattachedWorldEventsInRange(
                scope,
                batch.sourceFromMillis(),
                batch.sourceToMillis() + WORLD_EVENT_ATTACHMENT_GRACE_MILLIS
        );

        boolean summaryWritten = existing.isPresent();
        if (existing.isEmpty() && compaction.hasSummary()) {
            DubheStmBlock stm = toStmBlock(scope, batch, compaction.summary(), attachedWorldEvents, outcome.completionTokens());
            if (!memorySystem.appendStmBlock(scope, stm)) {
                return false;
            }
            if (existingStmFor(scope, batch).isEmpty()) {
                return false;
            }
            summaryWritten = true;
        }

        List<DubheMemoryEvent> events = new ArrayList<>(toEvents(scope, unitId, batch, compaction));
        // 世界事件直通：即使这个单元没有摘要，也仍然要落成事件（只需要单元 id，不需要摘要正文）。
        events.addAll(worldEventLinker.directEventsFor(unitId, attachedWorldEvents));
        if (!events.isEmpty()) {
            memorySystem.ensureStorageManifest(scope);
            if (!memorySystem.events().appendAll(scope, events).success()) {
                return false;
            }
        }

        appendPreferences(scope, batch, compaction);

        // 单元压缩是静默的：原文<b>不移出</b>窗口、摘要块<b>不变</b>、prompt 逐字节不变。
        // 唯一的移出时刻是换代（maybeSwapGeneration），它按"已被摘要覆盖"的边界清 raw。
        if (!summaryWritten && recentDialogueSystem != null) {
            // 例外：没有摘要就没有单元块来锚定覆盖游标，这段 raw 必须当场移出，否则会被反复压缩。
            recentDialogueSystem.dropCoveredRaw(scope, lastTurnId(batch));
        }
        return summaryWritten;
    }

    /**
     * 换代：压缩窗口到水位时，把本代单元摘要推进摘要块，并清掉压缩窗口里"已被覆盖"的 raw。
     *
     * <p>这是唯一允许 prompt 前缀变化的时刻。没有可晋升的单元就不换代（宁可再等一轮，也不产生
     * 一个空代次把账本搅乱）。</p>
     */
    private boolean maybeSwapGeneration(DubheScope scope) {
        if (recentDialogueSystem == null || !recentDialogueSystem.shouldSwapGeneration(scope)) {
            return false;
        }
        int generation = memorySystem.promoteSummaryGeneration(scope);
        if (generation <= 0) {
            return false;
        }
        recentDialogueSystem.swapGeneration(scope, generation, memorySystem.stmCoveredThroughTurnId(scope));
        return true;
    }

    /**
     * 一次 TASK 车道调用，产出三要素。
     *
     * <p>请求 = {@code system(规则+schema) + raw 轮次 + 指令}：system 与指令都是每次压缩逐字节相同的
     * 常量段，raw 轮次每行带 1-based 序号（{@code turnRefs} 抄的就是这个序号）。</p>
     */
    private CompletableFuture<DubheCompactionOutcome> compact(DubheRawTurnBatch batch, String worldId) {
        CompletableFuture<DubheCompactionOutcome> future = new CompletableFuture<>();
        TianshuEnvelope envelope = llmClient.submitDetached(compactionRequest(batch, worldId), new DubheLlmRequestHandler() {
            @Override
            public void onResult(LLMPromptResultPayload payload) {
                String text = cleanModelTaskText(payload == null ? "" : payload.text());
                if (payload == null || !payload.isCompleted() || text.isBlank()) {
                    // 把"为什么没成"交给词表：记忆维护失败的原因和对话失败是同一套原因。
                    reportFailure(DubheFailureCause.classify(
                            payload == null ? null : payload.errorCode(),
                            payload == null ? null : payload.errorMessage()
                    ));
                    future.complete(null);
                    return;
                }
                DubheMemoryCompressionResult result = compressionParser.parse(text, batch);
                future.complete(new DubheCompactionOutcome(result, completionTokenCount(payload, result.summary())));
            }

            @Override
            public void onCancelled(DubheTurnCancellation cancellation) {
                future.complete(null);
            }
        }, Duration.ofSeconds(TASK_STAGE_TIMEOUT_SECONDS));
        return withTaskTimeout(future, envelope, null);
    }

    private LLMPromptRequestPayload compactionRequest(DubheRawTurnBatch batch, String worldId) {
        List<DubheRawTurn> turns = batch.turns();
        StringBuilder rendered = new StringBuilder();
        for (int index = 0; index < turns.size(); index++) {
            String line = promptRepository.compactTurnLine(turns.get(index), index + 1);
            if (!line.isBlank()) {
                rendered.append(line).append('\n');
            }
        }
        List<LLMPromptRequestPayload.MessageItemPayload> messages = new ArrayList<>();
        String system = promptRepository.compactSystemPrompt();
        if (!system.isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.system(system));
        }
        if (!rendered.isEmpty()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.user(rendered.toString().stripTrailing()));
        }
        String instruction = promptRepository.compactInstruction(worldId, turns.size());
        if (!instruction.isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.user(instruction));
        }
        int reasoningTier = thinkingEnhancement.getAsBoolean() ? 2 : 1;
        return new LLMPromptRequestPayload(
                "dubhe.memory.compact." + batch.stmId(),
                messages,
                false,
                false,
                COMPACT_MAX_TOKENS,
                reasoningTier,
                new LLMPromptRequestPayload.SamplingPayload(COMPACT_TEMPERATURE, null, null, null)
        );
    }

    private DubheStmBlock toStmBlock(
            DubheScope scope,
            DubheRawTurnBatch batch,
            String summary,
            List<DubheAttachedWorldEvent> attachedWorldEvents,
            int completionTokens
    ) {
        return new DubheStmBlock(
                batch.stmId(),
                "",
                scope.worldId(),
                System.currentTimeMillis(),
                batch.sourceFromMillis(),
                batch.sourceToMillis(),
                "",
                "",
                batch.turns().size(),
                completionTokens,
                summary,
                worldEventLinker.attachedEventIds(attachedWorldEvents),
                firstTurnId(batch),
                lastTurnId(batch)
        );
    }

    /** 单元覆盖的第一轮 id：换代裁剪与溯源归集都以它为左边界。 */
    private static String firstTurnId(DubheRawTurnBatch batch) {
        return batch == null || batch.turns().isEmpty() ? "" : batch.turns().get(0).id();
    }

    /** 单元覆盖的最后一轮 id：它就是"覆盖游标"，压缩选批与换代裁剪都以它为界。 */
    private static String lastTurnId(DubheRawTurnBatch batch) {
        return batch == null || batch.turns().isEmpty() ? "" : batch.turns().get(batch.turns().size() - 1).id();
    }

    /** 事件落库形态：不去重、不合并；每条都带轮级溯源与模型给的静态分。 */
    private List<DubheMemoryEvent> toEvents(DubheScope scope, String unitId, DubheRawTurnBatch batch, DubheMemoryCompressionResult compaction) {
        if (compaction == null || compaction.events().isEmpty()) {
            return List.of();
        }
        long happenedAtMillis = batch.sourceToMillis();
        List<DubheMemoryEvent> events = new ArrayList<>();
        for (DubheMemoryCompressedEvent event : compaction.events()) {
            events.add(new DubheMemoryEvent(
                    "",
                    event.fact(),
                    "",
                    unitId,
                    SOURCE_KIND_COMPACT_EVENT,
                    scope.worldId(),
                    "",
                    "",
                    false,
                    System.currentTimeMillis(),
                    happenedAtMillis,
                    List.of(),
                    event.turnRefs(),
                    event.importance()
            ));
        }
        return events;
    }

    /** 偏好按玩家 UUID 落库，不按世界（记忆设计 §4.5）。 */
    private void appendPreferences(DubheScope scope, DubheRawTurnBatch batch, DubheMemoryCompressionResult compaction) {
        if (compaction == null || compaction.preferences().isEmpty()) {
            return;
        }
        List<DubhePlayerPreference> preferences = new ArrayList<>();
        for (DubheMemoryCompressedPreference preference : compaction.preferences()) {
            preferences.add(new DubhePlayerPreference(
                    "",
                    scope.sharedUserId(),
                    preference.fact(),
                    preference.confidence(),
                    preference.turnRefs(),
                    batch.batchId(),
                    System.currentTimeMillis()
            ));
        }
        memorySystem.preferences().appendAll(scope.sharedUserId(), preferences);
    }

    private Optional<DubheStmBlock> existingStmFor(DubheScope scope, DubheRawTurnBatch batch) {
        if (batch == null || batch.isEmpty() || batch.stmId().isBlank()) {
            return Optional.empty();
        }
        return memorySystem.stmBlocks().loadAll(scope).stream()
                .filter(block -> batch.stmId().equals(block.id()))
                .findFirst();
    }

    /** 这个压缩单元是否已经落过事件。不是去重：只用于判断要不要再叫一次模型。 */
    private boolean hasEventsForStm(DubheScope scope, String stmId) {
        if (stmId == null || stmId.isBlank()) {
            return false;
        }
        return memorySystem.events().loadAll(scope).stream()
                .anyMatch(event -> stmId.equals(event.stmId()));
    }

    private <T> CompletableFuture<T> withTaskTimeout(CompletableFuture<T> future, TianshuEnvelope envelope, T fallback) {
        future.orTimeout(TASK_STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    if (envelope != null) {
                        llmClient.cancelRequest(envelope.envelopeId(), DubheTurnCancellation.expired("AX memory task timed out"));
                    }
                    return fallback;
                });
        return future.exceptionally(error -> fallback);
    }

    private CompletableFuture<VectorRebuildResult> rebuildMissingVectors(DubheScope scope, AtomicBoolean startedStatusPublished, long generation) {
        CompletableFuture<VectorRebuildResult> done = new CompletableFuture<>();
        primitiveClient.requestStatus("dubhe.memory.embedding.status", status -> {
            if (!isCurrent(generation)) {
                done.complete(VectorRebuildResult.none());
                return;
            }
            if (!completed(status) || !embeddingUsable(status.runtimeSnapshot())) {
                done.complete(VectorRebuildResult.none());
                return;
            }
            String namespace = status.runtimeSnapshot().embeddingNamespace();
            List<DubheMemoryEvent> withoutVector = eventsWithoutVector(scope, namespace);
            if (withoutVector.isEmpty()) {
                done.complete(new VectorRebuildResult(namespace, 0));
                return;
            }
            publishStartedStatus(startedStatusPublished);
            embedBatches(scope, namespace, status.runtimeSnapshot().embeddingModelName(), withoutVector, 0, 0, done, generation);
        });
        return done;
    }

    private void embedBatches(DubheScope scope, String namespace, String modelName, List<DubheMemoryEvent> events, int from, int written, CompletableFuture<VectorRebuildResult> done, long generation) {
        if (!isCurrent(generation)) {
            done.complete(new VectorRebuildResult(namespace, written));
            return;
        }
        if (from >= events.size()) {
            done.complete(new VectorRebuildResult(namespace, written));
            return;
        }
        int to = Math.min(events.size(), from + VECTOR_BATCH_SIZE);
        List<DubheMemoryEvent> batch = events.subList(from, to);
        primitiveClient.requestEmbedding("dubhe.memory.embedding.batch." + from, batch.stream().map(DubheMemoryEvent::fact).toList(), result -> {
            if (!isCurrent(generation)) {
                done.complete(new VectorRebuildResult(namespace, written));
                return;
            }
            int nextWritten = written;
            if (completed(result)) {
                List<DubheEventVector> vectors = toVectors(batch, result, modelName, namespace);
                if (!vectors.isEmpty()) {
                    memorySystem.ensureStorageManifest(scope);
                    memorySystem.vectors().appendAll(scope, vectors);
                    nextWritten += vectors.size();
                }
            }
            embedBatches(scope, namespace, modelName, events, to, nextWritten, done, generation);
        });
    }

    private CompletableFuture<Integer> projectMemoryRag(DubheScope scope, String embeddingNamespace, AtomicBoolean startedStatusPublished, long generation) {
        if (!isCurrent(generation) || ragProjectionService == null || embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }
        publishStartedStatus(startedStatusPublished);
        memorySystem.invalidateRetrievalIndex(scope);
        return ragProjectionService.project(scope, embeddingNamespace)
                .thenApply(projected -> isCurrent(generation) ? projected : 0)
                .exceptionally(ignored -> 0);
    }

    private boolean isCurrent(long generation) {
        return generation > 0L && lifecycleGeneration.get() == generation;
    }

    private void publishStartedStatus(AtomicBoolean published) {
        if (published != null && !published.compareAndSet(false, true)) {
            return;
        }
        adapter.publishModuleStatus(ModuleStatus.keyed(
                DubheProtocolAdapter.MODULE_ID,
                "memory.maintenance",
                STATUS_KEY_STARTED,
                ModuleStatusSeverity.NOTICE,
                3_000L,
                Map.of()
        ));
    }

    private void publishCompleteStatus() {
        adapter.publishModuleStatus(ModuleStatuses.readyKeyed(
                DubheProtocolAdapter.MODULE_ID,
                STATUS_KEY_COMPLETE
        ));
    }

    private void publishFailedStatus() {
        adapter.publishModuleStatus(ModuleStatuses.failedKeyed(
                DubheProtocolAdapter.MODULE_ID,
                STATUS_KEY_FAILED
        ));
    }

    public DubheRecentDialogueSystem recentDialogueSystem() {
        return recentDialogueSystem;
    }

    /**
     * 向量补齐的<b>对账查询</b>：列出"已有事件、但索引里还没有对应向量"的事件，交给 embedding 重算。
     *
     * <p>这不是去重，也不许删：embedding 失败（模型未就绪、批次超时、进程中断）过的事件不会自己
     * 长出向量，只有这个查询能把它们找回来补齐；删掉它，RAG 会静默缺块——那些事件永远召不回来
     * 却也不报错。判据是 {@link DubheEventVector#idempotencyKey()}（与写入侧同一份键），
     * 所以补齐重跑同一批事件不会写出重复向量。</p>
     */
    private List<DubheMemoryEvent> eventsWithoutVector(DubheScope scope, String namespace) {
        List<DubheMemoryEvent> events = memorySystem.events().loadAll(scope);
        if (events.isEmpty()) {
            return List.of();
        }
        Set<String> indexed = new HashSet<>();
        for (DubheEventVector vector : memorySystem.vectors().load(scope, namespace)) {
            indexed.add(vector.idempotencyKey());
        }
        return events.stream()
                .filter(event -> !indexed.contains(DubheEventVector.idempotencyKey(event.id(), event.factHash())))
                .toList();
    }

    private int completionTokenCount(LLMPromptResultPayload payload, String text) {
        int completionTokens = payload == null || payload.usage() == null ? 0 : payload.usage().completionTokens();
        if (completionTokens > 0) {
            return completionTokens;
        }
        OptionalInt counted = primitiveClient.countTokens("dubhe.memory.stm.token", text);
        return counted.orElse(0);
    }

    private String cleanModelTaskText(String text) {
        return text == null ? "" : text.strip();
    }

    private List<DubheEventVector> toVectors(List<DubheMemoryEvent> events, LLMPrimitiveResultPayload result, String fallbackModelName, String fallbackNamespace) {
        List<DubheEventVector> vectors = new ArrayList<>();
        List<LLMPrimitiveResultPayload.EmbedResultPayload> embeds = result.embedResults();
        int count = Math.min(events.size(), embeds.size());
        for (int i = 0; i < count; i++) {
            DubheMemoryEvent event = events.get(i);
            LLMPrimitiveResultPayload.EmbedResultPayload embed = embeds.get(i);
            if (embed == null || embed.vector().length == 0) {
                continue;
            }
            String namespace = embed.embeddingNamespace().isBlank() ? fallbackNamespace : embed.embeddingNamespace();
            String modelName = embed.embeddingModelName().isBlank() ? fallbackModelName : embed.embeddingModelName();
            vectors.add(new DubheEventVector(
                    event.id(),
                    event.factHash(),
                    modelName,
                    namespace,
                    embed.dimension(),
                    embed.vector(),
                    System.currentTimeMillis()
            ));
        }
        return vectors;
    }

    private boolean completed(LLMPrimitiveResultPayload result) {
        return result != null && LLMPrimitiveResultPayload.STATUS_COMPLETED.equals(result.status());
    }

    private boolean embeddingUsable(LLMRuntimeSnapshotPayload snapshot) {
        return snapshot != null && snapshot.embeddingAvailable() && !snapshot.embeddingNamespace().isBlank();
    }

    /** 一次压缩调用的产出：解析结果 + 本次 completion 的 token 数（用于 STM 的记账字段）。 */
    private record DubheCompactionOutcome(DubheMemoryCompressionResult result, int completionTokens) {
    }

    private record VectorRebuildResult(String embeddingNamespace, int rebuiltVectors) {
        private VectorRebuildResult {
            embeddingNamespace = embeddingNamespace == null ? "" : embeddingNamespace.trim();
            rebuiltVectors = Math.max(0, rebuiltVectors);
        }

        private static VectorRebuildResult none() {
            return new VectorRebuildResult("", 0);
        }
    }

    private static final class DubheStorageSafeName {
        private DubheStorageSafeName() {
        }

        private static String of(String value) {
            if (value == null || value.isBlank()) {
                return "unknown";
            }
            return value.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
    }
}
