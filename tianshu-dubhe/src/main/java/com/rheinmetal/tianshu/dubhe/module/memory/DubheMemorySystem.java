package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.dubhe.core.context.DubheMemoryWindowPolicy;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.DubheMemoryRetrievalPolicy;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index.DubheMemoryPosition;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index.DubheMemoryRetrievalIndex;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index.DubheMemoryRetrievalIndexCache;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index.DubheMemoryRetrievalIndexSnapshot;
import com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index.DubheMemoryRetrievalIndexSnapshotStore;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheAttachedWorldEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheAttachedWorldEventBuffer;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheEventVector;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheEventVectorStore;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEventStore;
import com.rheinmetal.tianshu.dubhe.module.memory.preference.DubhePlayerPreferenceStore;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlockStore;
import com.rheinmetal.tianshu.dubhe.module.memory.summary.DubheMemorySummaryBlock;
import com.rheinmetal.tianshu.dubhe.module.memory.summary.DubheMemorySummaryBlockStore;
import com.rheinmetal.tianshu.dubhe.module.memory.summary.DubheMemorySummaryGeneration;

public final class DubheMemorySystem {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;
    /**
     * 当前生效的窗口策略：构造期是兜底值，每个解析成功的轮次由 {@link #applyWindowPolicy} 换成真实预算。
     * 摘要块容量（{@code summaryBlockTokenBudget}）也在其中，所以它必须逐轮可换。
     */
    private volatile DubheMemoryWindowPolicy windowPolicy;
    private final DubheStmBlockStore stmBlockStore;
    private final DubheMemorySummaryBlockStore summaryBlockStore;
    private final DubheMemoryEventStore eventStore;
    private final DubhePlayerPreferenceStore preferenceStore;
    private final DubheAttachedWorldEventBuffer attachedWorldEventBuffer;
    private final DubheEventVectorStore vectorStore;
    private final DubheMemoryStatsStore statsStore;
    private final DubheMemoryStorageManifestStore manifestStore;
    private final DubheMemoryRetrievalIndexSnapshotStore retrievalIndexSnapshotStore;
    private final DubheMemoryStorageCompatibilityChecker compatibilityChecker;
    private final DubheMemoryRetrievalIndexCache retrievalIndexCache = new DubheMemoryRetrievalIndexCache();
    private final DubheMemoryRetrievalPolicy retrievalPolicy;

    public DubheMemorySystem(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this(layout, jsonStore, null);
    }

    public DubheMemorySystem(DubheStorageLayout layout, DubheJsonStore jsonStore, DubheMemoryWindowPolicy policy) {
        this(layout, jsonStore, policy, DubheMemoryRetrievalPolicy.DEFAULT);
    }

    public DubheMemorySystem(
            DubheStorageLayout layout,
            DubheJsonStore jsonStore,
            DubheMemoryWindowPolicy policy,
            DubheMemoryRetrievalPolicy retrievalPolicy
    ) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.windowPolicy = policy == null ? DubheMemoryWindowPolicy.DEFAULT : policy;
        this.stmBlockStore = new DubheStmBlockStore(layout, jsonStore);
        this.summaryBlockStore = new DubheMemorySummaryBlockStore(layout, jsonStore);
        this.eventStore = new DubheMemoryEventStore(layout, jsonStore);
        this.preferenceStore = new DubhePlayerPreferenceStore(layout, jsonStore);
        this.attachedWorldEventBuffer = new DubheAttachedWorldEventBuffer();
        this.vectorStore = new DubheEventVectorStore(layout, jsonStore);
        this.statsStore = new DubheMemoryStatsStore(layout, jsonStore);
        this.manifestStore = new DubheMemoryStorageManifestStore(layout, jsonStore);
        this.retrievalIndexSnapshotStore = new DubheMemoryRetrievalIndexSnapshotStore(layout, jsonStore);
        this.compatibilityChecker = new DubheMemoryStorageCompatibilityChecker(layout, jsonStore);
        this.retrievalPolicy = retrievalPolicy == null ? DubheMemoryRetrievalPolicy.DEFAULT : retrievalPolicy;
    }

    /** 逐轮套用真实预算：摘要块容量跟着 {@code W} 走，不能停在构造期兜底值。 */
    public void applyWindowPolicy(DubheMemoryWindowPolicy policy) {
        if (policy != null) {
            this.windowPolicy = policy;
        }
    }

    public DubheMemorySnapshot load(DubheScope scope) {
        DubheScope effectiveScope = scope == null ? DubheScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return DubheMemorySnapshot.empty();
        }
        return new DubheMemorySnapshot(List.of(), summaryBlock(effectiveScope));
    }

    /** 摘要块账本（换代记录）。 */
    public DubheMemorySummaryBlockStore summaries() {
        return summaryBlockStore;
    }

    /**
     * 构建当前摘要块：账本里的晋升顺序（新→旧）取前缀，直到 {@code summaryBlockTokenBudget} 用完。
     *
     * <p>预算是"块的总容量"；装不下的旧单元只从渲染里消失，账本与摘要库都不动，其事件照常可检索。
     * 最新的一份永远保留——否则换代之后边界接续会变成空，断层就没有"接续"可言了。</p>
     */
    public DubheMemorySummaryBlock summaryBlock(DubheScope scope) {
        DubheScope effectiveScope = scope == null ? DubheScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return DubheMemorySummaryBlock.empty();
        }
        List<DubheMemorySummaryGeneration> generations = summaryBlockStore.loadGenerations(effectiveScope);
        if (generations.isEmpty()) {
            return DubheMemorySummaryBlock.empty();
        }
        int generation = generations.get(generations.size() - 1).generation();
        Map<String, DubheStmBlock> blocksById = new java.util.LinkedHashMap<>();
        for (DubheStmBlock block : stmBlockStore.loadAll(effectiveScope)) {
            if (block != null && !block.isEmpty()) {
                blocksById.putIfAbsent(block.id(), block);
            }
        }
        List<DubheStmBlock> promoted = new java.util.ArrayList<>();
        int dropped = 0;
        int tokens = 0;
        int budget = windowPolicy.summaryBlockTokenBudget();
        for (int index = generations.size() - 1; index >= 0; index--) {
            for (String stmId : generations.get(index).stmIds()) {
                DubheStmBlock block = blocksById.get(stmId);
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
            return new DubheMemorySummaryBlock(generation, List.of(), 0, dropped);
        }
        return new DubheMemorySummaryBlock(generation, memoryBlockViews(effectiveScope, promoted), tokens, dropped);
    }

    /**
     * 换代时把"还没晋升的单元摘要"写进账本（append-only），返回新代次；没有可晋升的就返回 0。
     *
     * <p>晋升顺序 = 新→旧（{@code sourceToMillis} 降序）：块里越靠前的越靠近 system，也越靠近当下。</p>
     */
    public int promoteSummaryGeneration(DubheScope scope) {
        if (!storageReadyForWrite(scope)) {
            return 0;
        }
        Set<String> promoted = new java.util.LinkedHashSet<>();
        for (DubheMemorySummaryGeneration generation : summaryBlockStore.loadGenerations(scope)) {
            promoted.addAll(generation.stmIds());
        }
        List<String> pending = stmBlockStore.loadAll(scope).stream()
                .filter(block -> block != null && !block.isEmpty() && !promoted.contains(block.id()))
                .sorted(java.util.Comparator
                        .comparingLong(DubheStmBlock::sourceToMillis)
                        .thenComparingLong(DubheStmBlock::createdAtMillis)
                        .reversed())
                .map(DubheStmBlock::id)
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
    public String stmCoveredThroughTurnId(DubheScope scope) {
        DubheScope effectiveScope = scope == null ? DubheScope.unknown() : scope;
        if (!effectiveScope.writable()) {
            return "";
        }
        return stmBlockStore.loadAll(effectiveScope).stream()
                .filter(block -> block != null && !block.isEmpty())
                .max(java.util.Comparator.comparingLong(DubheStmBlock::sourceToMillis))
                .map(DubheStmBlock::sourceToTurnId)
                .orElse("");
    }

    public boolean appendStmBlock(DubheScope scope, DubheStmBlock block) {
        if (!storageReadyForWrite(scope)) {
            return false;
        }
        return stmBlockStore.append(scope, block);
    }

    public boolean appendMemoryEvent(DubheScope scope, DubheMemoryEvent event) {
        if (!storageReadyForWrite(scope)) {
            return false;
        }
        boolean written = eventStore.append(scope, event).success();
        if (written) {
            retrievalIndexCache.invalidate(scope);
        }
        return written;
    }

    public void appendAttachedWorldEvent(DubheScope scope, DubheAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        if (scope == null || !scope.writable()) {
            return;
        }
        attachedWorldEventBuffer.append(scope, event);
    }

    public void appendEventVector(DubheScope scope, DubheEventVector vector) {
        if (!storageReadyForWrite(scope)) {
            return;
        }
        vectorStore.append(scope, vector);
        retrievalIndexCache.invalidate(scope);
    }

    public List<DubheAttachedWorldEvent> unattachedWorldEventsInRange(DubheScope scope, long fromMillis, long toMillis) {
        if (scope == null || !scope.writable() || fromMillis <= 0L || toMillis < fromMillis) {
            return List.of();
        }
        Set<String> attachedIds = stmBlockStore.loadAll(scope).stream()
                .flatMap(block -> block.attachedEventIds().stream())
                .collect(Collectors.toSet());
        return attachedWorldEventBuffer.loadInRange(scope, fromMillis, toMillis, attachedIds);
    }

    public List<DubheMemoryBlockView> memoryBlockViews(DubheScope scope, List<DubheStmBlock> blocks) {
        if (scope == null || !scope.writable() || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, String> attachedTextsById = attachedWorldEventBuffer.loadAll(scope).stream()
                .collect(Collectors.toMap(
                        DubheAttachedWorldEvent::id,
                        DubheMemorySystem::attachedText,
                        (first, second) -> first
                ));
        return blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .map(block -> new DubheMemoryBlockView(
                        block,
                        block.attachedEventIds().stream()
                                .map(attachedTextsById::get)
                                .filter(text -> text != null && !text.isBlank())
                                .toList()
                ))
                .toList();
    }

    public DubheStmBlockStore stmBlocks() {
        return stmBlockStore;
    }

    public DubheMemoryEventStore events() {
        return eventStore;
    }

    /** 玩家级偏好存储：键是玩家 UUID，不随世界隔离（记忆设计 §4.5）。 */
    public DubhePlayerPreferenceStore preferences() {
        return preferenceStore;
    }

    public DubheAttachedWorldEventBuffer attachedWorldEventBuffer() {
        return attachedWorldEventBuffer;
    }

    public DubheEventVectorStore vectors() {
        return vectorStore;
    }

    public DubheMemoryStatsStore stats() {
        return statsStore;
    }

    public DubheMemoryRetrievalIndexSnapshotStore retrievalIndexSnapshots() {
        return retrievalIndexSnapshotStore;
    }

    public DubheMemoryRetrievalIndex retrievalIndex(DubheScope scope, String embeddingNamespace) {
        if (scope == null || !scope.writable() || embeddingNamespace == null || embeddingNamespace.isBlank()) {
            return DubheMemoryRetrievalIndex.empty(embeddingNamespace);
        }
        DubheMemoryRetrievalIndexCache.SourceStamp sourceStamp = retrievalIndexSourceStamp(scope, embeddingNamespace);
        return retrievalIndexCache.get(
                scope,
                embeddingNamespace,
                sourceStamp,
                () -> buildRetrievalIndex(scope, embeddingNamespace, sourceStamp)
        );
    }

    public void invalidateRetrievalIndex(DubheScope scope) {
        retrievalIndexCache.invalidate(scope);
    }

    private DubheMemoryRetrievalIndex buildRetrievalIndex(
            DubheScope scope,
            String embeddingNamespace,
            DubheMemoryRetrievalIndexCache.SourceStamp sourceStamp
    ) {
        java.util.Optional<DubheMemoryRetrievalIndexSnapshot> snapshot = retrievalIndexSnapshotStore.load(scope, embeddingNamespace)
                .filter(candidate -> candidate.matches(embeddingNamespace, sourceStamp));
        boolean reusableSnapshot = snapshot
                .map(candidate -> candidate.eventCount() > 0
                        && candidate.effectiveMappingByEventId().size() >= candidate.eventCount()
                        && !candidate.l1Clusters().isEmpty()
                        && !candidate.l2EffectiveMappings().isEmpty())
                .orElse(false);
        DubheMemoryRetrievalIndex index = DubheMemoryRetrievalIndex.build(
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

    public void ensureStorageManifest(DubheScope scope) {
        ensureWorldManifest(scope);
    }

    public DubheMemoryStorageCompatibilityReport checkStorageCompatibility(DubheScope scope) {
        if (compatibilityChecker == null) {
            return new DubheMemoryStorageCompatibilityReport(false, false, List.of(
                    DubheMemoryStorageCompatibilityReport.Issue.error("DUBHE_MEMORY_COMPATIBILITY_CHECKER_MISSING", "storage compatibility checker is not available")
            ));
        }
        return compatibilityChecker.check(scope);
    }

    private void ensureWorldManifest(DubheScope scope) {
        if (manifestStore != null) {
            manifestStore.ensureWorldManifest(scope);
        }
    }

    private boolean storageReadyForWrite(DubheScope scope) {
        if (scope == null || !scope.writable()) {
            return false;
        }
        if (compatibilityChecker != null) {
            DubheMemoryStorageCompatibilityReport before = compatibilityChecker.check(scope);
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

    private DubheMemoryRetrievalIndexCache.SourceStamp retrievalIndexSourceStamp(DubheScope scope, String embeddingNamespace) {
        return new DubheMemoryRetrievalIndexCache.SourceStamp(
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


    private static String attachedText(DubheAttachedWorldEvent event) {
        if (event == null || event.isEmpty()) {
            return "";
        }
        return event.text().isBlank() ? event.eventType() : event.text();
    }
}
