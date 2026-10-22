package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryPosition;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndex;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndexCache;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndexSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndexSnapshotStore;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEventBuffer;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVectorStore;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEventStore;
import com.rheinmetal.tianshu.function.auxilium.module.memory.preference.AXPlayerPreferenceStore;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlockStore;
import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryBlock;
import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryBlockStore;
import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryGeneration;

public final class AXMemorySystem {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;
    /**
     * 当前生效的窗口策略：构造期是兜底值，每个解析成功的轮次由 {@link #applyWindowPolicy} 换成真实预算。
     * 摘要块容量（{@code summaryBlockTokenBudget}）也在其中，所以它必须逐轮可换。
     */
    private volatile AXMemoryWindowPolicy windowPolicy;
    private final AXStmBlockStore stmBlockStore;
    private final AXMemorySummaryBlockStore summaryBlockStore;
    private final AXMemoryEventStore eventStore;
    private final AXPlayerPreferenceStore preferenceStore;
    private final AXAttachedWorldEventBuffer attachedWorldEventBuffer;
    private final AXEventVectorStore vectorStore;
    private final AXMemoryStatsStore statsStore;
    private final AXMemoryStorageManifestStore manifestStore;
    private final AXMemoryRetrievalIndexSnapshotStore retrievalIndexSnapshotStore;
    private final AXMemoryStorageCompatibilityChecker compatibilityChecker;
    private final AXMemoryRetrievalIndexCache retrievalIndexCache = new AXMemoryRetrievalIndexCache();
    private final AXMemoryRetrievalPolicy retrievalPolicy;

    public AXMemorySystem(AXStorageLayout layout, AXJsonStore jsonStore) {
        this(layout, jsonStore, null);
    }

    public AXMemorySystem(AXStorageLayout layout, AXJsonStore jsonStore, AXMemoryWindowPolicy policy) {
        this(layout, jsonStore, policy, AXMemoryRetrievalPolicy.DEFAULT);
    }

    public AXMemorySystem(
            AXStorageLayout layout,
            AXJsonStore jsonStore,
            AXMemoryWindowPolicy policy,
            AXMemoryRetrievalPolicy retrievalPolicy
    ) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.windowPolicy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
        this.stmBlockStore = new AXStmBlockStore(layout, jsonStore);
        this.summaryBlockStore = new AXMemorySummaryBlockStore(layout, jsonStore);
        this.eventStore = new AXMemoryEventStore(layout, jsonStore);
        this.preferenceStore = new AXPlayerPreferenceStore(layout, jsonStore);
        this.attachedWorldEventBuffer = new AXAttachedWorldEventBuffer();
        this.vectorStore = new AXEventVectorStore(layout, jsonStore);
        this.statsStore = new AXMemoryStatsStore(layout, jsonStore);
        this.manifestStore = new AXMemoryStorageManifestStore(layout, jsonStore);
        this.retrievalIndexSnapshotStore = new AXMemoryRetrievalIndexSnapshotStore(layout, jsonStore);
        this.compatibilityChecker = new AXMemoryStorageCompatibilityChecker(layout, jsonStore);
        this.retrievalPolicy = retrievalPolicy == null ? AXMemoryRetrievalPolicy.DEFAULT : retrievalPolicy;
    }

    /** 逐轮套用真实预算：摘要块容量跟着 {@code W} 走，不能停在构造期兜底值。 */
    public void applyWindowPolicy(AXMemoryWindowPolicy policy) {
        if (policy != null) {
            this.windowPolicy = policy;
        }
    }

    public AXMemorySnapshot load(AXScope scope) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return AXMemorySnapshot.empty();
        }
        return new AXMemorySnapshot(List.of(), summaryBlock(effectiveScope));
    }

    /** 摘要块账本（换代记录）。 */
    public AXMemorySummaryBlockStore summaries() {
        return summaryBlockStore;
    }

    /**
     * 构建当前摘要块：账本里的晋升顺序（新→旧）取前缀，直到 {@code summaryBlockTokenBudget} 用完。
     *
     * <p>预算是"块的总容量"；装不下的旧单元只从渲染里消失，账本与摘要库都不动，其事件照常可检索。
     * 最新的一份永远保留——否则换代之后边界接续会变成空，断层就没有"接续"可言了。</p>
     */
    public AXMemorySummaryBlock summaryBlock(AXScope scope) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return AXMemorySummaryBlock.empty();
        }
        List<AXMemorySummaryGeneration> generations = summaryBlockStore.loadGenerations(effectiveScope);
        if (generations.isEmpty()) {
            return AXMemorySummaryBlock.empty();
        }
        int generation = generations.get(generations.size() - 1).generation();
        Map<String, AXStmBlock> blocksById = new java.util.LinkedHashMap<>();
        for (AXStmBlock block : stmBlockStore.loadAll(effectiveScope)) {
            if (block != null && !block.isEmpty()) {
                blocksById.putIfAbsent(block.id(), block);
            }
        }
        List<AXStmBlock> promoted = new java.util.ArrayList<>();
        int dropped = 0;
        int tokens = 0;
        int budget = windowPolicy.summaryBlockTokenBudget();
        for (int index = generations.size() - 1; index >= 0; index--) {
            for (String stmId : generations.get(index).stmIds()) {
                AXStmBlock block = blocksById.get(stmId);
                if (block == null || block.isEmpty()) {
                    continue;
                }
                if (!promoted.isEmpty() && budget > 0 && tokens + block.tokenCount() > budget) {
                    dropped++;
                    continue;
                }
                promoted.add(block);
                tokens += Math.max(0, block.tokenCount());
            }
        }
        if (promoted.isEmpty()) {
            return new AXMemorySummaryBlock(generation, List.of(), 0, dropped);
        }
        return new AXMemorySummaryBlock(generation, memoryBlockViews(effectiveScope, promoted), tokens, dropped);
    }

    /**
     * 换代时把"还没晋升的单元摘要"写进账本（append-only），返回新代次；没有可晋升的就返回 0。
     *
     * <p>晋升顺序 = 新→旧（{@code sourceToMillis} 降序）：块里越靠前的越靠近 system，也越靠近当下。</p>
     */
    public int promoteSummaryGeneration(AXScope scope) {
        if (!storageReadyForWrite(scope)) {
            return 0;
        }
        Set<String> promoted = new java.util.LinkedHashSet<>();
        for (AXMemorySummaryGeneration generation : summaryBlockStore.loadGenerations(scope)) {
            promoted.addAll(generation.stmIds());
        }
        List<String> pending = stmBlockStore.loadAll(scope).stream()
                .filter(block -> block != null && !block.isEmpty() && !promoted.contains(block.id()))
                .sorted(java.util.Comparator
                        .comparingLong(AXStmBlock::sourceToMillis)
                        .thenComparingLong(AXStmBlock::createdAtMillis)
                        .reversed())
                .map(AXStmBlock::id)
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }
        int nextGeneration = summaryBlockStore.generation(scope) + 1;
        return summaryBlockStore.append(scope, nextGeneration, pending) ? nextGeneration : 0;
    }

    /**
     * 已有单元摘要覆盖到的最后一轮 id（{@code sourceToTurnId}，取时间上最新的那个单元）。
     *
     * <p>两个消费者共用它：压缩选批时跳过已覆盖的轮次（避免重复压缩同一段原文），换代时用它精确
     * 界定"哪些 raw 可以被移出窗口"。所以它算的是<b>摘要库</b>而不是账本——未晋升的单元也覆盖过
     * 原文。用轮 id 而不是时间戳：同一毫秒可以有多轮，时间边界会误伤边界后的原文。</p>
     */
    public String stmCoveredThroughTurnId(AXScope scope) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return "";
        }
        return stmBlockStore.loadAll(effectiveScope).stream()
                .filter(block -> block != null && !block.isEmpty())
                .max(java.util.Comparator.comparingLong(AXStmBlock::sourceToMillis))
                .map(AXStmBlock::sourceToTurnId)
                .orElse("");
    }

    public boolean appendStmBlock(AXScope scope, AXStmBlock block) {
        if (!storageReadyForWrite(scope)) {
            return false;
        }
        return stmBlockStore.append(scope, block);
    }

    public boolean appendMemoryEvent(AXScope scope, AXMemoryEvent event) {
        if (!storageReadyForWrite(scope)) {
            return false;
        }
        boolean written = eventStore.append(scope, event).success();
        if (written) {
            retrievalIndexCache.invalidate(scope);
        }
        return written;
    }

    public void appendAttachedWorldEvent(AXScope scope, AXAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        if (scope == null || !scope.writable()) {
            return;
        }
        attachedWorldEventBuffer.append(scope, event);
    }

    public void appendEventVector(AXScope scope, AXEventVector vector) {
        if (!storageReadyForWrite(scope)) {
            return;
        }
        vectorStore.append(scope, vector);
        retrievalIndexCache.invalidate(scope);
    }

    public List<AXAttachedWorldEvent> unattachedWorldEventsInRange(AXScope scope, long fromMillis, long toMillis) {
        if (scope == null || !scope.writable() || fromMillis <= 0L || toMillis < fromMillis) {
            return List.of();
        }
        Set<String> attachedIds = stmBlockStore.loadAll(scope).stream()
                .flatMap(block -> block.attachedEventIds().stream())
                .collect(Collectors.toSet());
        return attachedWorldEventBuffer.loadInRange(scope, fromMillis, toMillis, attachedIds);
    }

    public List<AXMemoryBlockView> memoryBlockViews(AXScope scope, List<AXStmBlock> blocks) {
        if (scope == null || !scope.writable() || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, String> attachedTextsById = attachedWorldEventBuffer.loadAll(scope).stream()
                .collect(Collectors.toMap(
                        AXAttachedWorldEvent::id,
                        AXMemorySystem::attachedText,
                        (first, second) -> first
                ));
        return blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .map(block -> new AXMemoryBlockView(
                        block,
                        block.attachedEventIds().stream()
                                .map(attachedTextsById::get)
                                .filter(text -> text != null && !text.isBlank())
                                .toList()
                ))
                .toList();
    }

    public AXStmBlockStore stmBlocks() {
        return stmBlockStore;
    }

    public AXMemoryEventStore events() {
        return eventStore;
    }

    /** 玩家级偏好存储：键是玩家 UUID，不随世界隔离（记忆设计 §4.5）。 */
    public AXPlayerPreferenceStore preferences() {
        return preferenceStore;
    }

    public AXAttachedWorldEventBuffer attachedWorldEventBuffer() {
        return attachedWorldEventBuffer;
    }

    public AXEventVectorStore vectors() {
        return vectorStore;
    }

    public AXMemoryStatsStore stats() {
        return statsStore;
    }

    public AXMemoryRetrievalIndexSnapshotStore retrievalIndexSnapshots() {
        return retrievalIndexSnapshotStore;
    }

    public AXMemoryRetrievalIndex retrievalIndex(AXScope scope, String embeddingNamespace) {
        if (scope == null || !scope.writable() || embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return AXMemoryRetrievalIndex.empty(embeddingNamespace);
        }
        AXMemoryRetrievalIndexCache.SourceStamp sourceStamp = retrievalIndexSourceStamp(scope, embeddingNamespace);
        return retrievalIndexCache.get(
                scope,
                embeddingNamespace,
                sourceStamp,
                () -> buildRetrievalIndex(scope, embeddingNamespace, sourceStamp)
        );
    }

    public void invalidateRetrievalIndex(AXScope scope) {
        retrievalIndexCache.invalidate(scope);
    }

    private AXMemoryRetrievalIndex buildRetrievalIndex(
            AXScope scope,
            String embeddingNamespace,
            AXMemoryRetrievalIndexCache.SourceStamp sourceStamp
    ) {
        java.util.Optional<AXMemoryRetrievalIndexSnapshot> snapshot = retrievalIndexSnapshotStore.load(scope, embeddingNamespace)
                .filter(candidate -> candidate.matches(embeddingNamespace, sourceStamp));
        boolean reusableSnapshot = snapshot
                .map(candidate -> candidate.eventCount() > 0
                        && candidate.effectiveMappingByEventId().size() >= candidate.eventCount()
                        && !candidate.l1Clusters().isEmpty()
                        && !candidate.l2EffectiveMappings().isEmpty())
                .orElse(false);
        AXMemoryRetrievalIndex index = AXMemoryRetrievalIndex.build(
                eventStore.loadAll(scope),
                vectorStore.load(scope, embeddingNamespace),
                embeddingNamespace,
                retrievalPolicy,
                reusableSnapshot ? snapshot.orElse(null) : null
        );
        if (!reusableSnapshot && !index.isEmpty()) {
            retrievalIndexSnapshotStore.write(scope, index.toSnapshot(sourceStamp));
        }
        return index;
    }

    public void ensureStorageManifest(AXScope scope) {
        ensureWorldManifest(scope);
    }

    public AXMemoryStorageCompatibilityReport checkStorageCompatibility(AXScope scope) {
        if (compatibilityChecker == null) {
            return new AXMemoryStorageCompatibilityReport(false, false, List.of(
                    AXMemoryStorageCompatibilityReport.Issue.error("AX_MEMORY_COMPATIBILITY_CHECKER_MISSING", "storage compatibility checker is not available")
            ));
        }
        return compatibilityChecker.check(scope);
    }

    private void ensureWorldManifest(AXScope scope) {
        if (manifestStore != null) {
            manifestStore.ensureWorldManifest(scope);
        }
    }

    private boolean storageReadyForWrite(AXScope scope) {
        if (scope == null || !scope.writable()) {
            return false;
        }
        if (compatibilityChecker != null) {
            AXMemoryStorageCompatibilityReport before = compatibilityChecker.check(scope);
            if (before.manifestPresent() && before.hasErrors()) {
                return false;
            }
        }
        ensureWorldManifest(scope);
        if (compatibilityChecker == null) {
            return true;
        }
        return !compatibilityChecker.check(scope).hasErrors();
    }

    private AXMemoryRetrievalIndexCache.SourceStamp retrievalIndexSourceStamp(AXScope scope, String embeddingNamespace) {
        return new AXMemoryRetrievalIndexCache.SourceStamp(
                fileSize(layout.eventsFile(scope)),
                fileModifiedAtMillis(layout.eventsFile(scope)),
                fileSize(layout.eventVectorsFile(scope, embeddingNamespace)),
                fileModifiedAtMillis(layout.eventVectorsFile(scope, embeddingNamespace))
        );
    }

    private long fileSize(Path path) {
        try {
            return path == null || !Files.isRegularFile(path) ? -1L : Files.size(path);
        } catch (Exception e) {
            return -1L;
        }
    }

    private long fileModifiedAtMillis(Path path) {
        try {
            return path == null || !Files.isRegularFile(path) ? -1L : Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return -1L;
        }
    }


    private static String attachedText(AXAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return "";
        }
        return event.text().isBlank() ? event.eventType() : event.text();
    }
}
