package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AXRawTurnWindow {
    /**
     * Volatile because the retention watermarks follow the per-turn LLM budget: the window is created
     * with the construction-time fallback policy and re-pointed on every resolved turn. A single
     * method always reads this field once, so one turn never mixes two policies.
     */
    private volatile AXMemoryWindowPolicy policy;
    private final Map<String, Deque<AXRawTurn>> turnsByWorld = new ConcurrentHashMap<>();
    /**
     * "窗口装满"信号：一次 append 触发了容量硬裁剪，说明压缩窗口已经顶到 {@code MAX_RAW_TOKENS}。
     *
     * <p>为什么需要它：{@code trim} 始终把总量压在 {@code MAX_RAW_TOKENS} 以内，所以"总量 ≥ 上限"
     * 只在整数正好对齐时才成立；真正稳定可达的判据是"这一轮又装不下了"。换代消费掉这个信号。</p>
     */
    private final Set<String> capacityEvictedWorlds = ConcurrentHashMap.newKeySet();

    public AXRawTurnWindow(AXMemoryWindowPolicy policy) {
        this.policy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
    }

    /** Replaces the window policy; called with the per-turn budget once it is resolved. */
    public void applyPolicy(AXMemoryWindowPolicy policy) {
        if (policy != null) {
            this.policy = policy;
        }
    }

    public void append(AXScope scope, AXRawTurn turn) {
        if (!writable(scope) || turn == null || turn.isEmpty()) {
            return;
        }
        Deque<AXRawTurn> turns = turnsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(turn);
            trim(scope, turns);
        }
    }

    public List<AXRawTurn> recent(AXScope scope) {
        AXMemoryWindowPolicy activePolicy = policy;
        if (!writable(scope) || activePolicy.recentRawKeepTokenMax() <= 0) {
            return List.of();
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return List.of();
        }
        List<AXRawTurn> snapshot;
        synchronized (turns) {
            snapshot = new ArrayList<>(turns);
        }
        List<TurnGroup> groups = turnGroups(snapshot);
        List<TurnGroup> selectedGroups = new ArrayList<>();
        int tokens = 0;
        for (int i = groups.size() - 1; i >= 0; i--) {
            TurnGroup group = groups.get(i);
            if (tokens + group.tokenCount() > activePolicy.recentRawKeepTokenMax()) {
                break;
            }
            selectedGroups.add(group);
            tokens += group.tokenCount();
            if (tokens >= activePolicy.recentRawKeepTokenMax()) {
                break;
            }
        }
        Collections.reverse(selectedGroups);
        return selectedGroups.stream()
                .flatMap(group -> group.turns().stream())
                .toList();
    }

    public List<AXRawTurn> snapshot(AXScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return List.of();
        }
        synchronized (turns) {
            return List.copyOf(turns);
        }
    }

    public Map<String, List<AXRawTurn>> snapshotAll() {
        Map<String, List<AXRawTurn>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<AXRawTurn>> entry : turnsByWorld.entrySet()) {
            Deque<AXRawTurn> turns = entry.getValue();
            if (turns == null) {
                continue;
            }
            synchronized (turns) {
                result.put(entry.getKey(), List.copyOf(turns));
            }
        }
        return Map.copyOf(result);
    }

    public void replace(AXScope scope, List<AXRawTurn> turns) {
        if (!writable(scope)) {
            return;
        }
        Deque<AXRawTurn> next = new ArrayDeque<>();
        if (turns != null) {
            for (AXRawTurn turn : turns) {
                if (turn != null && !turn.isEmpty()) {
                    next.addLast(turn);
                }
            }
        }
        trim(scope, next);
        Deque<AXRawTurn> existing = turnsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ArrayDeque<>());
        synchronized (existing) {
            existing.clear();
            existing.addAll(next);
        }
    }

    /**
     * 选出一个<b>压缩单元</b>：只从未被覆盖、且不属于保持窗口的轮次里，从最旧开始攒，
     * 攒满 {@code compressionUnitTokenBudget} 为止。
     *
     * <p>三条边界，缺一不可：</p>
     * <ul>
     *   <li>已覆盖（{@code 覆盖游标及其之前的轮次}）的轮次略过——它们已经有单元摘要，
     *       原文仍留在窗口里，等换代才移出；</li>
     *   <li>保持窗口（最近 {@code KEEP} 轮逐字原文）永不压缩，否则换代时会连它一起被清掉；</li>
     *   <li>攒不够一个完整单元就不压：半步单元会让摘要粒度退化成每几轮一份。</li>
     * </ul>
     *
     * <p>tokenizer 不可用时按字符密度折算，判据仍然可用。</p>
     */
    public AXRawTurnBatch selectCompressionBatch(AXScope scope, String coveredThroughTurnId) {
        AXMemoryWindowPolicy activePolicy = policy;
        int unitBudget = activePolicy.compressionUnitTokenBudget();
        if (!writable(scope) || unitBudget <= 0) {
            return AXRawTurnBatch.empty();
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return AXRawTurnBatch.empty();
        }
        synchronized (turns) {
            List<TurnGroup> groups = turnGroups(new ArrayList<>(turns));
            Set<String> keep = keepWindowTurnIds(scope);
            Set<String> uncovered = uncoveredTurnIds(turns, coveredThroughTurnId);
            int availableTokens = 0;
            int availableCharacters = 0;
            for (TurnGroup group : groups) {
                if (!compressible(group, uncovered, keep)) {
                    continue;
                }
                availableTokens += group.compressibleTokens();
                availableCharacters += group.compressibleCharacters();
            }
            boolean tokensKnown = availableTokens > 0;
            int requiredCharacters = activePolicy.charactersForTokens(unitBudget);
            if (tokensKnown ? availableTokens < unitBudget : availableCharacters < requiredCharacters) {
                return AXRawTurnBatch.empty();
            }
            List<AXRawTurn> selected = new ArrayList<>();
            int tokens = 0;
            int chars = 0;
            for (TurnGroup group : groups) {
                if (!compressible(group, uncovered, keep)) {
                    continue;
                }
                int groupTokens = group.compressibleTokens();
                int groupCharacters = group.compressibleCharacters();
                int used = tokensKnown ? tokens : chars;
                int groupSize = tokensKnown ? groupTokens : groupCharacters;
                int budget = tokensKnown ? unitBudget : requiredCharacters;
                if (!selected.isEmpty() && used + groupSize > budget) {
                    break;
                }
                selected.addAll(group.compressibleTurns());
                tokens += groupTokens;
                chars += groupCharacters;
                if ((tokensKnown ? tokens : chars) >= budget) {
                    break;
                }
            }
            return selected.isEmpty() ? AXRawTurnBatch.empty() : new AXRawTurnBatch("", selected, 0L, 0L, tokens, chars);
        }
    }

    /**
     * 一个轮次组是否可以被压进当前单元。
     *
     * <p>整组判断：只要组里有任何一轮已覆盖或属于保持窗口，整组都不动——保持窗口与已覆盖的原文
     * 都是不可再压缩的边界，切在组内会让摘要的区间锚对不上。</p>
     */
    private boolean compressible(TurnGroup group, Set<String> uncovered, Set<String> keep) {
        if (group == null || group.compressibleTokens() <= 0) {
            return false;
        }
        for (AXRawTurn turn : group.turns()) {
            if (!uncovered.contains(turn.id()) || keep.contains(turn.id())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 覆盖游标之后的轮次（即"还没被任何单元摘要覆盖"）。
     *
     * <p>游标是<b>轮 id</b>而不是时间戳：同一毫秒里可以有好几轮，用时间戳当边界会把边界后同毫秒的
     * 原文误判成已覆盖。游标不在窗口里（已被移出或在上一代被裁剪）时，视作"窗口里全部未覆盖"——
     * 窗口是按时间顺序的，丢失的游标只可能比窗口里所有轮次更旧。</p>
     */
    private Set<String> uncoveredTurnIds(Deque<AXRawTurn> turns, String coveredThroughTurnId) {
        Set<String> uncovered = new LinkedHashSet<>();
        boolean afterCursor = coveredThroughTurnId == null || coveredThroughTurnId.isBlank();
        for (AXRawTurn turn : turns) {
            if (!afterCursor) {
                if (coveredThroughTurnId.equals(turn.id())) {
                    afterCursor = true;
                }
                continue;
            }
            uncovered.add(turn.id());
        }
        return uncovered;
    }

    /** 保持窗口内的轮次 id（与 {@link #recent} 同一套判据，保证"注入什么就永不压缩什么"）。 */
    private Set<String> keepWindowTurnIds(AXScope scope) {
        Set<String> ids = new LinkedHashSet<>();
        for (AXRawTurn turn : recent(scope)) {
            ids.add(turn.id());
        }
        return ids;
    }

    /** 窗口当前保留的 raw token 总数（换代判据用它，而不是提示词水位）。 */
    public int rawTokenCount(AXScope scope) {
        if (!writable(scope)) {
            return 0;
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return 0;
        }
        List<AXRawTurn> snapshot;
        synchronized (turns) {
            snapshot = new ArrayList<>(turns);
        }
        int tokens = snapshot.stream().mapToInt(AXRawTurn::tokenCount).sum();
        if (tokens > 0) {
            return tokens;
        }
        return policy.tokensForCharacters(snapshot.stream().mapToInt(AXRawTurn::characterCount).sum());
    }

    /** 窗口当前生效的策略（只读）。 */
    public AXMemoryWindowPolicy policy() {
        return policy;
    }

    /**
     * 换代专用：把"已被压缩覆盖"的 raw 轮次移出窗口。
     *
     * <p>只移出同时满足两个条件的轮次：{@code createdAtMillis <= compressedThroughMillis}（确实被某个
     * 单元覆盖过）且不属于保持窗口（保持窗口是逐字历史，任何情况下都不移出）。没被覆盖的轮次一律
     * 留下——换代只清理压缩窗口，不清理"还没压缩过"的原文。</p>
     *
     * @return 实际移出的轮次数
     */
    public int dropCompressedTurns(AXScope scope, String coveredThroughTurnId) {
        if (!writable(scope) || coveredThroughTurnId == null || coveredThroughTurnId.isBlank()) {
            return 0;
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return 0;
        }
        Set<String> keep = keepWindowTurnIds(scope);
        synchronized (turns) {
            List<AXRawTurn> snapshot = new ArrayList<>(turns);
            int boundaryIndex = -1;
            for (int index = 0; index < snapshot.size(); index++) {
                if (coveredThroughTurnId.equals(snapshot.get(index).id())) {
                    boundaryIndex = index;
                    break;
                }
            }
            if (boundaryIndex < 0) {
                return 0;
            }
            List<AXRawTurn> remaining = new ArrayList<>(snapshot.size());
            int removed = 0;
            for (int index = 0; index < snapshot.size(); index++) {
                AXRawTurn turn = snapshot.get(index);
                if (index <= boundaryIndex && !keep.contains(turn.id()) && compressibleTurn(turn)) {
                    removed++;
                    continue;
                }
                remaining.add(turn);
            }
            if (removed == 0) {
                return 0;
            }
            turns.clear();
            turns.addAll(remaining);
            return removed;
        }
    }

    /** 单轮是否属于可压缩内容：世界事件不进单元，因此移出窗口时也要留下。 */
    private boolean compressibleTurn(AXRawTurn turn) {
        return turn != null && !turn.isEmpty() && !"world_event".equals(turn.role());
    }


    /** 压缩窗口是否已经装满（发生过容量硬裁剪，或总量已顶到上限）。 */
    public boolean capacityReached(AXScope scope) {
        if (!writable(scope)) {
            return false;
        }
        AXMemoryWindowPolicy activePolicy = policy;
        return capacityEvictedWorlds.contains(scope.worldId())
                || (activePolicy.maxRawTokenCount() > 0 && rawTokenCount(scope) >= activePolicy.maxRawTokenCount());
    }

    /** 换代后清掉"装满"信号，下一代重新判定。 */
    public void clearCapacityReached(AXScope scope) {
        if (scope != null) {
            capacityEvictedWorlds.remove(scope.worldId());
        }
    }

    private void trim(AXScope scope, Deque<AXRawTurn> turns) {
        List<TurnGroup> groups = turnGroups(new ArrayList<>(turns));
        int tokens = groups.stream().mapToInt(TurnGroup::tokenCount).sum();
        int characters = groups.stream().mapToInt(TurnGroup::characterCount).sum();
        for (int i = 0; i < groups.size() - 1 && exceedsRawCapacity(tokens, characters); i++) {
            TurnGroup removed = groups.get(i);
            for (int turnIndex = 0; turnIndex < removed.turns().size(); turnIndex++) {
                turns.removeFirst();
            }
            tokens -= removed.tokenCount();
            characters -= removed.characterCount();
            if (scope != null) {
                capacityEvictedWorlds.add(scope.worldId());
            }
        }
    }

    private boolean exceedsRawCapacity(int tokens, int characters) {
        AXMemoryWindowPolicy activePolicy = policy;
        return tokens > activePolicy.maxRawTokenCount() || characters > activePolicy.maxRawCharacters();
    }

    private boolean writable(AXScope scope) {
        return scope != null && scope.writable();
    }

    private List<TurnGroup> turnGroups(List<AXRawTurn> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        List<TurnGroup> groups = new ArrayList<>();
        int index = 0;
        while (index < snapshot.size()) {
            AXRawTurn turn = snapshot.get(index);
            if (turn == null || turn.isEmpty()) {
                index++;
                continue;
            }
            String key = dialogueBoundaryKey(turn);
            if (key.isBlank()) {
                groups.add(new TurnGroup(List.of(turn)));
                index++;
                continue;
            }
            int end = index;
            for (int cursor = index + 1; cursor < snapshot.size(); cursor++) {
                AXRawTurn candidate = snapshot.get(cursor);
                if (candidate == null || candidate.isEmpty()) {
                    continue;
                }
                String candidateKey = dialogueBoundaryKey(candidate);
                if (key.equals(candidateKey)) {
                    end = cursor;
                    continue;
                }
                if (candidateKey.isBlank()) {
                    end = cursor;
                    continue;
                }
                break;
            }
            List<AXRawTurn> groupTurns = new ArrayList<>();
            for (int cursor = index; cursor <= end; cursor++) {
                AXRawTurn candidate = snapshot.get(cursor);
                if (candidate != null && !candidate.isEmpty()) {
                    groupTurns.add(candidate);
                }
            }
            groups.add(new TurnGroup(groupTurns));
            index = end + 1;
        }
        return groups;
    }

    private String dialogueBoundaryKey(AXRawTurn turn) {
        if (turn == null || turn.iaTurnId() == null || turn.iaTurnId().isBlank()) {
            return "";
        }
        if (!"user".equals(turn.role()) && !"assistant".equals(turn.role())) {
            return "";
        }
        return turn.iaSessionId() + ":" + turn.iaTurnId();
    }

    private record TurnGroup(List<AXRawTurn> turns) {
        private TurnGroup {
            turns = turns == null ? List.of() : turns.stream()
                    .filter(turn -> turn != null && !turn.isEmpty())
                    .toList();
        }

        private int tokenCount() {
            return turns.stream().mapToInt(AXRawTurn::tokenCount).sum();
        }

        private int characterCount() {
            return turns.stream().mapToInt(AXRawTurn::characterCount).sum();
        }

        private int compressibleTokens() {
            return compressibleTurns().stream().mapToInt(AXRawTurn::tokenCount).sum();
        }

        private int compressibleCharacters() {
            return compressibleTurns().stream().mapToInt(AXRawTurn::characterCount).sum();
        }

        private List<AXRawTurn> compressibleTurns() {
            return turns.stream()
                    .filter(turn -> !"world_event".equals(turn.role()))
                    .toList();
        }
    }
}
