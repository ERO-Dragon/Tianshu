package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class AXMemoryRetriever {
    private static final float RAG_SEARCH_FLOOR = 0.01F;

    /**
     * L1/L2 命中条目的确定性排序：分数降序，同分按 entryId 升序。
     *
     * <p>只按分数排序时，同分条目的先后取决于提供方返回的顺序（索引遍历顺序），同一份历史在两台
     * 机器上可能给出不同的 STM 集合——前缀稳定性（03 §9）要求这里必须有确定性的第二级键。</p>
     */
    private static final Comparator<LLMCacheManageResultPayload.HitEntryPayload> HIT_ENTRY_ORDER =
            Comparator.comparingDouble(LLMCacheManageResultPayload.HitEntryPayload::score).reversed()
                    .thenComparing(LLMCacheManageResultPayload.HitEntryPayload::entryId);

    /**
     * STM 候选的确定性五级排序：总分 → 重要性 → 时间新鲜度 → 相关性 → STM id。
     *
     * <p>前四级取分越高越前，最后一级用 id 升序兜底——id 在同一个世界内唯一且稳定，于是"同输入两次
     * 排序结果一致"成为可断言的属性，而不是依赖 {@code HashMap} 遍历顺序的偶然事件。</p>
     */
    private static final Comparator<StmContribution> STM_ORDER =
            Comparator.comparingDouble(StmContribution::score).reversed()
                    .thenComparing(Comparator.comparingInt(StmContribution::maxImportance).reversed())
                    .thenComparing(Comparator.comparingDouble(StmContribution::maxRecency).reversed())
                    .thenComparing(Comparator.comparingDouble(StmContribution::maxRelevance).reversed())
                    .thenComparing(StmContribution::stmId);

    /**
     * 多命中加权：同一 STM 里被命中多次略加分，但它<b>不能</b>推翻"最强者优先"。
     *
     * <p>{@code score = max × (1 + WEIGHT × log1p(hits − 1))}：两次命中 +6.9%、五次 +16%，永远小于
     * 两级之间真实的分数差（分档线是 {@code 0.72 × top}）。这样既保留"多条证据更可信"，又不会让
     * 一堆弱命中把单条强命中挤下去。</p>
     */
    private static final double CORROBORATION_WEIGHT = 0.10D;

    private final AXMemorySystem memorySystem;
    private final AXLlmRagClient ragClient;
    private final AXMemoryRetrievalPolicy retrievalPolicy;
    private final ConcurrentMap<String, List<CompletableFuture<LLMCacheManageResultPayload>>> pendingByRequest = new ConcurrentHashMap<>();

    public AXMemoryRetriever(AXMemorySystem memorySystem, AXLlmRagClient ragClient) {
        this(memorySystem, ragClient, AXMemoryRetrievalPolicy.DEFAULT);
    }

    public AXMemoryRetriever(AXMemorySystem memorySystem, AXLlmRagClient ragClient, AXMemoryRetrievalPolicy retrievalPolicy) {
        this.memorySystem = Objects.requireNonNull(memorySystem, "memorySystem");
        this.ragClient = Objects.requireNonNull(ragClient, "ragClient");
        this.retrievalPolicy = retrievalPolicy == null ? AXMemoryRetrievalPolicy.DEFAULT : retrievalPolicy;
    }

    public void retrieve(AXMemoryRetrievalRequest request, Completion completion) {
        Objects.requireNonNull(completion, "completion");
        Completion safeCompletion = once(completion);
        if (request == null || !request.scope().writable() || request.queryText().isBlank() || request.maxBlocks() <= 0) {
            safeCompletion.complete(AXMemoryRetrievalResult.empty());
            return;
        }
        String requestKey = request.request() == null ? "" : request.request().requestKey();
        List<CompletableFuture<LLMCacheManageResultPayload>> tracked = new CopyOnWriteArrayList<>();
        if (!requestKey.isBlank()) {
            pendingByRequest.put(requestKey, tracked);
        }
        try {
            searchAsync(request, tracked).whenComplete((result, error) -> {
                if (!requestKey.isBlank()) {
                    pendingByRequest.remove(requestKey, tracked);
                }
                safeCompletion.complete(error == null ? result : AXMemoryRetrievalResult.empty());
            });
        } catch (RuntimeException exception) {
            if (!requestKey.isBlank()) {
                pendingByRequest.remove(requestKey, tracked);
            }
            safeCompletion.complete(AXMemoryRetrievalResult.empty());
        }
    }

    public CompletableFuture<AXMemoryRetrievalResult> retrieveAsync(AXMemoryRetrievalRequest request) {
        CompletableFuture<AXMemoryRetrievalResult> future = new CompletableFuture<>();
        retrieve(request, future::complete);
        return future;
    }

    public void cancel(String requestKey, String reason) {
        if (requestKey == null || requestKey.isBlank()) {
            return;
        }
        List<CompletableFuture<LLMCacheManageResultPayload>> tracked = pendingByRequest.remove(requestKey);
        if (tracked == null) {
            return;
        }
        for (CompletableFuture<LLMCacheManageResultPayload> future : tracked) {
            ragClient.cancelFuture(future, reason);
        }
    }

    private CompletableFuture<AXMemoryRetrievalResult> searchAsync(
            AXMemoryRetrievalRequest request,
            List<CompletableFuture<LLMCacheManageResultPayload>> tracked
    ) {
        String l1Uid = AXMemoryRagUids.l1(request.scope());
        int l1TopK = Math.max(retrievalPolicy.minRoutedL1Clusters(), request.maxBlocks());
        CompletableFuture<LLMCacheManageResultPayload> l1Future = ragClient.searchUid(l1Uid, request.queryText(), l1TopK, RAG_SEARCH_FLOOR);
        tracked.add(l1Future);
        return l1Future
                .thenCompose(l1Result -> {
                    List<String> l2ClusterIds = hitEntryIds(l1Result);
                    if (l2ClusterIds.isEmpty()) {
                        return CompletableFuture.completedFuture(AXMemoryRetrievalResult.empty());
                    }
                    List<CompletableFuture<LLMCacheManageResultPayload>> futures = l2ClusterIds.stream()
                            .map(clusterId -> ragClient.searchUid(
                                    AXMemoryRagUids.l2(request.scope(), clusterId),
                                    request.queryText(),
                                    Math.max(request.maxBlocks(), retrievalPolicy.l2EffectiveMappingMaxSize()),
                                    RAG_SEARCH_FLOOR
                            ))
                            .toList();
                    tracked.addAll(futures);
                    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> {
                                List<LLMCacheManageResultPayload> l2Results = futures.stream()
                                        .map(CompletableFuture::join)
                                        .toList();
                                return search(request, l2Results);
                            });
                });
    }

    private AXMemoryRetrievalResult search(AXMemoryRetrievalRequest request, List<LLMCacheManageResultPayload> l2Results) {
        if (l2Results == null || l2Results.isEmpty()) {
            return AXMemoryRetrievalResult.empty();
        }
        // Chain expansion is the tier-sensitive part of the policy: it follows this turn's real
        // context budget instead of a constant, and falls back to the compact tier when unknown.
        AXMemoryRetrievalPolicy policy = retrievalPolicy.forTier(request.contextTier());
        Map<String, AXMemoryEvent> eventsById = memorySystem.events().loadAll(request.scope()).stream()
                .collect(Collectors.toMap(AXMemoryEvent::id, event -> event, (first, second) -> first, LinkedHashMap::new));
        long nowMillis = System.currentTimeMillis();
        Map<String, StmContribution> contributions = new HashMap<>();
        Set<String> subColdStmIds = new HashSet<>();
        for (LLMCacheManageResultPayload result : l2Results) {
            if (result == null || !result.success() || result.hits().isEmpty()) {
                continue;
            }
            for (LLMCacheManageResultPayload.HitGroupPayload group : result.hits()) {
                String effectiveMappingId = AXMemoryRagUids.l2ClusterId(group.uid());
                for (LLMCacheManageResultPayload.HitEntryPayload hit : group.entries()) {
                    AXMemoryEvent event = eventsById.get(hit.entryId());
                    if (event == null || event.stmId().isBlank()) {
                        continue;
                    }
                    // score = relevance × recency × importance（§6.1）。三个因子都有地板，所以
                    // "极旧但重要"或"很新但弱相关"的事件都不会被乘成 0 而消失候选。
                    double score = AXMemoryScoring.score(hit.score(), event, nowMillis);
                    if (score < policy.coldScoreCutoff()) {
                        if (score > 0.0D) {
                            subColdStmIds.add(event.stmId());
                        }
                        continue;
                    }
                    contributions.computeIfAbsent(event.stmId(), StmContribution::new)
                            .add(event, effectiveMappingId, score, nowMillis);
                }
            }
        }
        if (contributions.isEmpty()) {
            return AXMemoryRetrievalResult.empty();
        }
        List<AXStmBlock> allBlocks = memorySystem.stmBlocks().loadAll(request.scope());
        Map<String, AXStmBlock> blocksById = new LinkedHashMap<>();
        Map<String, Integer> blockOrder = new HashMap<>();
        int order = 0;
        for (AXStmBlock block : allBlocks) {
            blocksById.putIfAbsent(block.id(), block);
            blockOrder.putIfAbsent(block.id(), order++);
        }
        List<SelectedBlock> selected = selectBlocks(request, policy, contributions, subColdStmIds, blocksById, blockOrder, allBlocks);
        List<AXStmBlock> timeline = selected.stream()
                .sorted(Comparator.comparingInt(SelectedBlock::order))
                .map(SelectedBlock::block)
                .toList();
        Set<String> selectedStmIds = timeline.stream()
                .map(AXStmBlock::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<AXMemoryRetrievalTrace> traces = selectedStmIds.stream()
                .map(contributions::get)
                .filter(Objects::nonNull)
                .map(StmContribution::toTrace)
                .toList();
        return new AXMemoryRetrievalResult(memorySystem.memoryBlockViews(request.scope(), timeline), traces);
    }

    private List<String> hitEntryIds(LLMCacheManageResultPayload result) {
        if (result == null || !result.success() || result.hits().isEmpty()) {
            return List.of();
        }
        return result.hits().stream()
                .filter(Objects::nonNull)
                .flatMap(group -> group.entries().stream())
                .filter(Objects::nonNull)
                .sorted(HIT_ENTRY_ORDER)
                .map(LLMCacheManageResultPayload.HitEntryPayload::entryId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }


    private List<SelectedBlock> selectBlocks(
            AXMemoryRetrievalRequest request,
            AXMemoryRetrievalPolicy policy,
            Map<String, StmContribution> contributions,
            Set<String> subColdStmIds,
            Map<String, AXStmBlock> blocksById,
            Map<String, Integer> blockOrder,
            List<AXStmBlock> allBlocks
    ) {
        List<StmContribution> ordered = contributions.values().stream()
                .sorted(STM_ORDER)
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        // 分档的基准是乘积分（relevance × recency × importance），所以 topScore 也必须是乘积分；
        // 用 relevance 当基准会让"很新但弱相关"的候选拿到它不该有的相对线。
        double topScore = ordered.stream()
                .mapToDouble(StmContribution::maxScore)
                .max()
                .orElse(0.0D);
        // Tiering is relative: the cutoffs are derived from this request's own top score, so a weak and a
        // strong result set are tiered the same way (see AXMemoryRetrievalPolicy). The absolute floor is
        // the only fixed part, and it is what keeps a uniformly bad set out of Hot.
        double hotCutoff = policy.hotScoreCutoff(topScore);
        double warmCutoff = policy.warmScoreCutoff(topScore);
        double coldCutoff = policy.coldScoreCutoff();
        List<StmContribution> hotCandidates = new ArrayList<>();
        List<StmContribution> warmCandidates = new ArrayList<>();
        List<StmContribution> coldCandidates = new ArrayList<>();
        for (StmContribution contribution : ordered) {
            double score = contribution.maxScore();
            if (contribution.isForcedHot() || score >= hotCutoff) {
                // 强制 Hot 线（§6.3.4）：重要性 ≥ 8 且相关性 ≥ 0.60 的位置/偏好类事实直接进 Hot，
                // 不受 0.72 × top 相对线约束——否则"本轮恰好有一条更热的闲聊"就会把它挤到 Warm。
                hotCandidates.add(contribution);
            } else if (score >= warmCutoff) {
                warmCandidates.add(contribution);
            } else if (score >= coldCutoff) {
                // Cold 可容线（§6.3.4）：重要性 < 4 的弱事实不进 Cold，避免"问过一句话"占住长期配额。
                if (contribution.isColdEligible()) {
                    coldCandidates.add(contribution);
                }
            }
        }
        List<StmContribution> eligible = new ArrayList<>();
        eligible.addAll(hotCandidates);
        eligible.addAll(warmCandidates);
        eligible.addAll(coldCandidates);
        int maxBlocks = request.maxBlocks();
        int tokenBudget = request.tokenBudget();
        Set<String> seen = new HashSet<>();
        List<SelectedBlock> selected = new ArrayList<>();
        int[] tokens = {0};
        TierBudget hotTier = new TierBudget(policy.hotBlockBudget(maxBlocks), policy.hotTokenBudget(tokenBudget));
        selectFromTier(hotCandidates, policy, hotTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        TierBudget warmTier = new TierBudget(
                Math.min(policy.warmBlockBudget(maxBlocks), maxBlocks - selected.size()),
                tokenBudget <= 0 ? 0 : Math.min(policy.warmTokenBudget(tokenBudget), Math.max(0, tokenBudget - tokens[0]))
        );
        selectFromTier(warmCandidates, policy, warmTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        TierBudget coldTier = new TierBudget(
                Math.min(policy.coldBlockBudget(maxBlocks), maxBlocks - selected.size()),
                tokenBudget <= 0 ? 0 : Math.min(policy.coldTokenBudget(tokenBudget), Math.max(0, tokenBudget - tokens[0]))
        );
        selectFromTier(coldCandidates, policy, coldTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        if (selected.size() < maxBlocks) {
            TierBudget remainingTier = new TierBudget(
                    maxBlocks - selected.size(),
                    tokenBudget <= 0 ? 0 : Math.max(0, tokenBudget - tokens[0])
            );
            selectFromTier(eligible, policy, remainingTier, topScore, subColdStmIds, blocksById, blockOrder, allBlocks, seen, selected, tokens, maxBlocks);
        }
        return selected;
    }

    private void selectFromTier(
            List<StmContribution> candidates,
            AXMemoryRetrievalPolicy policy,
            TierBudget tier,
            double topScore,
            Set<String> subColdStmIds,
            Map<String, AXStmBlock> blocksById,
            Map<String, Integer> blockOrder,
            List<AXStmBlock> allBlocks,
            Set<String> seen,
            List<SelectedBlock> selected,
            int[] tokens,
            int maxBlocks
    ) {
        if (tier.blockBudget() <= 0) {
            return;
        }
        int selectedAnchors = 0;
        for (StmContribution contribution : candidates) {
            if (selectedAnchors >= tier.blockBudget()) {
                break;
            }
            if (selected.size() >= maxBlocks) {
                break;
            }
            AXStmBlock block = blocksById.get(contribution.stmId());
            if (block == null || block.isEmpty()) {
                continue;
            }
            List<AXStmBlock> chain = chainExpansionCandidates(policy, block, contribution, topScore, subColdStmIds, blocksById, blockOrder, allBlocks);
            boolean selectedAny = false;
            for (AXStmBlock candidate : chain) {
                if (selected.size() >= maxBlocks) {
                    break;
                }
                if (candidate == null || candidate.isEmpty() || !seen.add(candidate.id())) {
                    continue;
                }
                if (tier.tokenBudget() > 0 && tokens[0] + candidate.tokenCount() > tier.tokenBudget()) {
                    seen.remove(candidate.id());
                    continue;
                }
                selected.add(new SelectedBlock(candidate, blockOrder.getOrDefault(candidate.id(), Integer.MAX_VALUE)));
                tokens[0] += candidate.tokenCount();
                selectedAny = true;
            }
            if (selectedAny) {
                selectedAnchors++;
            }
        }
    }

    private record TierBudget(int blockBudget, int tokenBudget) {
    }

    /**
     * Expands one confident anchor along the STM chain.
     *
     * <p>The walk is driven by the policy of the current context tier: {@code chainExpansionRadius}
     * bounds how many linked hops each direction may be followed and {@code chainExpansionBreadth}
     * bounds how many neighbour blocks the anchor may pull in. Both are data, not constants in this
     * method, and the returned list is ordered anchor-first, nearest link first, so a truncating block
     * or token budget always keeps the anchor before its neighbours.</p>
     */
    private List<AXStmBlock> chainExpansionCandidates(
            AXMemoryRetrievalPolicy policy,
            AXStmBlock center,
            StmContribution contribution,
            double topScore,
            Set<String> subColdStmIds,
            Map<String, AXStmBlock> blocksById,
            Map<String, Integer> blockOrder,
            List<AXStmBlock> allBlocks
    ) {
        int radius = policy.chainExpansionRadius();
        int breadth = policy.chainExpansionBreadth();
        if (radius <= 0 || breadth <= 0 || !shouldExpandChain(policy, contribution, topScore)) {
            return List.of(center);
        }
        Integer centerOrder = blockOrder.get(center.id());
        if (centerOrder == null || centerOrder < 0 || centerOrder >= allBlocks.size()) {
            return List.of(center);
        }
        // The timeline window keeps a stale chain link from dragging in a block far away in file order.
        int windowFrom = Math.max(0, centerOrder - radius);
        int windowTo = Math.min(allBlocks.size() - 1, centerOrder + radius);
        List<AXStmBlock> backwardArm = chainArm(center, blocksById, radius, true, subColdStmIds);
        List<AXStmBlock> forwardArm = chainArm(center, blocksById, radius, false, subColdStmIds);
        List<AXStmBlock> candidates = new ArrayList<>();
        candidates.add(center);
        Set<String> admitted = new HashSet<>();
        admitted.add(center.id());
        int expanded = 0;
        for (int index = 0; index < radius && expanded < breadth; index++) {
            expanded += admitChainBlock(candidates, admitted, armEntry(backwardArm, index), breadth - expanded, windowFrom, windowTo, blockOrder);
            expanded += admitChainBlock(candidates, admitted, armEntry(forwardArm, index), breadth - expanded, windowFrom, windowTo, blockOrder);
        }
        // A block may carry only one side of a link (the store patches the predecessor lazily, and a
        // chain is normalised by the maintenance pass). The pre-tier behaviour matched such a neighbour
        // in either direction, so it stays admitted here as the last, capped safety net.
        for (int index = windowFrom; index <= windowTo && expanded < breadth; index++) {
            AXStmBlock candidate = allBlocks.get(index);
            if (!linksTo(center, candidate) || (subColdStmIds != null && subColdStmIds.contains(candidate.id()))) {
                continue;
            }
            expanded += admitChainBlock(candidates, admitted, candidate, breadth - expanded, windowFrom, windowTo, blockOrder);
        }
        return candidates;
    }

    private AXStmBlock armEntry(List<AXStmBlock> arm, int index) {
        return index >= 0 && index < arm.size() ? arm.get(index) : null;
    }

    /**
     * Collects the chain links reachable from {@code center} in one direction, nearest link first.
     *
     * <p>{@code radius} bounds the number of links kept. A sub-cold block is skipped but does not stop
     * the walk, so one cold neighbour cannot cut the chain in half.</p>
     */
    private List<AXStmBlock> chainArm(
            AXStmBlock center,
            Map<String, AXStmBlock> blocksById,
            int radius,
            boolean backward,
            Set<String> subColdStmIds
    ) {
        List<AXStmBlock> arm = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(center.id());
        String cursor = backward ? center.previousStmId() : center.nextStmId();
        int hops = 0;
        while (hops < radius && cursor != null && !cursor.isBlank() && visited.add(cursor)) {
            hops++;
            AXStmBlock block = blocksById.get(cursor);
            if (block == null || block.isEmpty()) {
                break;
            }
            boolean subCold = subColdStmIds != null && subColdStmIds.contains(block.id());
            if (!subCold) {
                arm.add(block);
            }
            cursor = backward ? block.previousStmId() : block.nextStmId();
        }
        return arm;
    }

    /** Whether either side of the pair holds a chain pointer to the other. */
    private boolean linksTo(AXStmBlock center, AXStmBlock candidate) {
        if (center == null || candidate == null || center.id().equals(candidate.id())) {
            return false;
        }
        return center.id().equals(candidate.previousStmId())
                || center.id().equals(candidate.nextStmId())
                || candidate.id().equals(center.previousStmId())
                || candidate.id().equals(center.nextStmId());
    }

    /** Adds one neighbour if the breadth budget allows it and the timeline window still covers it. */
    private int admitChainBlock(
            List<AXStmBlock> candidates,
            Set<String> admitted,
            AXStmBlock candidate,
            int remainingBreadth,
            int windowFrom,
            int windowTo,
            Map<String, Integer> blockOrder
    ) {
        if (remainingBreadth <= 0 || candidate == null || candidate.isEmpty() || !admitted.add(candidate.id())) {
            return 0;
        }
        Integer candidateOrder = blockOrder.get(candidate.id());
        if (candidateOrder == null || candidateOrder < windowFrom || candidateOrder > windowTo) {
            return 0;
        }
        candidates.add(candidate);
        return 1;
    }

    private boolean shouldExpandChain(AXMemoryRetrievalPolicy policy, StmContribution contribution, double topScore) {
        if (contribution == null || topScore <= 0.0D) {
            return false;
        }
        if (contribution.maxRelevance() < policy.chainExpansionMinScore()) {
            return false;
        }
        return contribution.maxRelevance() >= topScore * policy.chainExpansionScoreRatio();
    }

    public interface Completion {
        void complete(AXMemoryRetrievalResult result);
    }

    private Completion once(Completion completion) {
        AtomicBoolean completed = new AtomicBoolean(false);
        return result -> {
            if (completed.compareAndSet(false, true)) {
                completion.complete(result == null ? AXMemoryRetrievalResult.empty() : result);
            }
        };
    }

    private record SelectedBlock(AXStmBlock block, int order) {
    }

    /**
     * 一个 STM 块的命中聚合。
     *
     * <p>块的分档分 = <b>块内最强事件的乘积分</b> × 多命中加权（{@link #CORROBORATION_WEIGHT}）。
     * 用 max 而不是求和：分档线是相对于 {@code topScore} 的百分比，求和会让"命中次数多"压过"命中更
     * 强"，让一条弱相关事件堆出来的块挤掉真正相关的那块。同时把用于五级 tie-break 的三个因子
     * （重要性 / 新鲜度 / 相关性）各自记下最大值，排序键全部来自已持久化字段，可重算、可断言。</p>
     */
    private static final class StmContribution {
        private final String stmId;
        private double maxScore;
        private double maxRelevance;
        private double maxRecency;
        private int maxImportance;
        private int hitCount;
        private boolean forcedHot;
        private boolean coldEligible;
        private final List<AXMemoryRetrievalTrace.EventHit> hits = new ArrayList<>();

        private StmContribution(String stmId) {
            this.stmId = stmId;
        }

        private void add(AXMemoryEvent event, String effectiveMappingId, double score, long nowMillis) {
            String mapping = effectiveMappingId == null || effectiveMappingId.isBlank()
                    ? "unmapped"
                    : effectiveMappingId;
            double recency = event == null ? 0.0D : AXMemoryScoring.recency(event, nowMillis);
            double relevance = relevanceOf(event, score, recency);
            maxScore = Math.max(maxScore, score);
            maxRelevance = Math.max(maxRelevance, relevance);
            maxRecency = Math.max(maxRecency, recency);
            if (event != null) {
                maxImportance = Math.max(maxImportance, event.importance());
                forcedHot = forcedHot || AXMemoryScoring.isForcedHot(event, relevance);
                coldEligible = coldEligible || AXMemoryScoring.isColdEligible(event);
            }
            hitCount++;
            hits.add(hit(event, mapping, relevance));
        }

        /**
         * 反推相关性：{@code score = relevance × recency × importanceFactor}，另两个因子都能从事件重算，
         * 于是不需要把相关性单独穿进来——打分只有 {@link AXMemoryScoring} 一个入口。
         *
         * <p>反推是为了让 tie-break 的第 3/4 级（新鲜度、相关性）与第 1 级（乘积分）永远自洽：它们来自
         * 同一次计算，不存在"排序键与显示分不同源"的可能。</p>
         */
        private static double relevanceOf(AXMemoryEvent event, double score, double recency) {
            if (event == null) {
                return score;
            }
            double divisor = recency * AXMemoryScoring.importanceFactor(event);
            return divisor <= 0.0D ? score : Math.min(1.0D, score / divisor);
        }

        private String stmId() {
            return stmId;
        }

        private double score() {
            if (hitCount <= 1) {
                return maxScore;
            }
            return maxScore * (1.0D + CORROBORATION_WEIGHT * Math.log1p(hitCount - 1));
        }

        private double maxScore() {
            return maxScore;
        }

        private double maxRelevance() {
            return maxRelevance;
        }

        private double maxRecency() {
            return maxRecency;
        }

        private int maxImportance() {
            return maxImportance;
        }

        private boolean isForcedHot() {
            return forcedHot;
        }

        private boolean isColdEligible() {
            return coldEligible;
        }

        private AXMemoryRetrievalTrace toTrace() {
            return new AXMemoryRetrievalTrace(
                    stmId,
                    score(),
                    hits.stream()
                            .sorted(Comparator.comparingDouble(AXMemoryRetrievalTrace.EventHit::relevance).reversed()
                                    .thenComparing(AXMemoryRetrievalTrace.EventHit::eventId))
                            .toList()
            );
        }

        private AXMemoryRetrievalTrace.EventHit hit(AXMemoryEvent event, String mapping, double relevance) {
            if (event == null) {
                return new AXMemoryRetrievalTrace.EventHit("", "", mapping, relevance);
            }
            return new AXMemoryRetrievalTrace.EventHit(event.id(), event.factHash(), mapping, relevance);
        }
    }
}
