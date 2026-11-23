package com.rheinmetal.tianshu.dubhe.module.recentdialogue;

import com.rheinmetal.tianshu.dubhe.agent.context.DubheMemoryWindowPolicy;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

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

public final class DubheRawTurnWindow {
    /**
     * Volatile because the retention watermarks follow the per-turn LLM budget: the window is created
     * with the construction-time fallback policy and re-pointed on every resolved turn. A single
     * method always reads this field once, so one turn never mixes two policies.
     */
    private volatile DubheMemoryWindowPolicy policy;
    private final Map<String, Deque<DubheRawTurn>> turnsByWorld = new ConcurrentHashMap<>();
    /**
     * "窗口装满"信号：一次 append 触发了容量硬裁剪，说明压缩窗口已经顶到 {@code MAX_RAW_TOKENS}。
     *
     * <p>为什么需要它：{@code trim} 始终把总量压在 {@code MAX_RAW_TOKENS} 以内，所以"总量 ≥ 上限"
     * 只在整数正好对齐时才成立；真正稳定可达的判据是"这一轮又装不下了"。换代消费掉这个信号。</p>
     */
    private final Set<String> capacityEvictedWorlds = ConcurrentHashMap.newKeySet();

    public DubheRawTurnWindow(DubheMemoryWindowPolicy policy) {
        this.policy = policy == null ? DubheMemoryWindowPolicy.DEFAULT : policy;
    }

    /** Replaces the window policy; called with the per-turn budget once it is resolved. */
    public void applyPolicy(DubheMemoryWindowPolicy policy) {
        if (policy != null) {
            this.policy = policy;
        }
    }

    /**
     * 追加一轮，返回"这次追加是否挤掉了旧轮次"。
     *
     * <p>返回值不是诊断，而是落盘的判据：写前落盘（每一轮追加一行）只在<b>没有</b>挤掉旧轮次时才与窗口内容
     * 逐条一致；一旦挤掉，文件里就多出了窗口里已经没有的轮次，必须整份重写把它们清掉，否则重启会把淘汰过的
     * 原文读回来——历史被"复活"比丢掉它更糟。</p>
     */
    public boolean append(DubheScope scope, DubheRawTurn turn) {
        if (!writable(scope) || turn == null || turn.isEmpty()) {
            return false;
        }
        Deque<DubheRawTurn> turns = turnsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ArrayDeque<>());
        synchronized (turns) {
            int before = turns.size();
            turns.addLast(turn);
            trim(scope, turns);
            return turns.size() != before + 1;
        }
    }

    /** 最近一轮（含世界事件与助手答句）；恢复时用它判断"最后一问有没有答"。 */
    public DubheRawTurn lastTurn(DubheScope scope) {
        if (!writable(scope)) {
            return null;
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return null;
        }
        synchronized (turns) {
            return turns.peekLast();
        }
    }

    /**
     * 当前窗口里有几轮：调用方拿它当"这一段历史之后的东西都归某一轮"的水位。
     *
     * <p>它只是长度，不是内容；回滚靠它加 {@link #truncateFrom} 完成。</p>
     */
    public int size(DubheScope scope) {
        if (!writable(scope)) {
            return 0;
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return 0;
        }
        synchronized (turns) {
            return turns.size();
        }
    }

    /**
     * 撤回到水位：把 {@code mark} 之后追加的轮次丢掉，游戏事件例外。
     *
     * <p>为什么要保留游戏事件：它们写的是世界自己发生的事（谁死了、钓到了什么），与某一问成不成功无关；
     * 一次失败的提问不该顺带把这期间的"事实"一起抹掉。</p>
     *
     * @param mark            之前 {@link #size} 的值；小于 0 表示这一轮没有水位，什么也不做
     * @param keepWorldEvents 是否保留被撤回区间里的游戏事件
     * @return 实际移出的轮次数
     */
    public int truncateFrom(DubheScope scope, int mark, boolean keepWorldEvents) {
        if (!writable(scope) || mark < 0) {
            return 0;
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return 0;
        }
        synchronized (turns) {
            List<DubheRawTurn> snapshot = new ArrayList<>(turns);
            if (mark >= snapshot.size()) {
                return 0;
            }
            List<DubheRawTurn> kept = new ArrayList<>(snapshot.subList(0, mark));
            int removed = 0;
            for (int index = mark; index < snapshot.size(); index++) {
                DubheRawTurn turn = snapshot.get(index);
                if (keepWorldEvents && turn.worldEventRole()) {
                    kept.add(turn);
                } else {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            turns.clear();
            turns.addAll(kept);
            return removed;
        }
    }

    /** 窗口里的全部轮次（快照）：保留区对齐需要"整段"而不是只取最近几轮。 */
    public List<DubheRawTurn> all(DubheScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return List.of();
        }
        synchronized (turns) {
            return List.copyOf(turns);
        }
    }

    /** 保持窗口内的轮次 id；重置对齐以它为"预算内"的基准集合。 */
    public Set<String> keepTurnIds(DubheScope scope) {
        return keepWindowTurnIds(scope);
    }
    public List<DubheRawTurn> recent(DubheScope scope) {
        DubheMemoryWindowPolicy activePolicy = policy;
        if (!writable(scope) || activePolicy.recentRawKeepTokenMax() <= 0) {
            return List.of();
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return List.of();
        }
        List<DubheRawTurn> snapshot;
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

    public List<DubheRawTurn> snapshot(DubheScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return List.of();
        }
        synchronized (turns) {
            return List.copyOf(turns);
        }
    }

    public Map<String, List<DubheRawTurn>> snapshotAll() {
        Map<String, List<DubheRawTurn>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<DubheRawTurn>> entry : turnsByWorld.entrySet()) {
            Deque<DubheRawTurn> turns = entry.getValue();
            if (turns == null) {
                continue;
            }
            synchronized (turns) {
                result.put(entry.getKey(), List.copyOf(turns));
            }
        }
        return Map.copyOf(result);
    }

    public void replace(DubheScope scope, List<DubheRawTurn> turns) {
        if (!writable(scope)) {
            return;
        }
        Deque<DubheRawTurn> next = new ArrayDeque<>();
        if (turns != null) {
            for (DubheRawTurn turn : turns) {
                if (turn != null && !turn.isEmpty()) {
                    next.addLast(turn);
                }
            }
        }
        trim(scope, next);
        Deque<DubheRawTurn> existing = turnsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ArrayDeque<>());
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
    public DubheRawTurnBatch selectCompressionBatch(DubheScope scope, String coveredThroughTurnId) {
        DubheMemoryWindowPolicy activePolicy = policy;
        int unitBudget = activePolicy.compressionUnitTokenBudget();
        if (!writable(scope) || unitBudget <= 0) {
            return DubheRawTurnBatch.empty();
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return DubheRawTurnBatch.empty();
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
                return DubheRawTurnBatch.empty();
            }
            List<DubheRawTurn> selected = new ArrayList<>();
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
            return selected.isEmpty() ? DubheRawTurnBatch.empty() : new DubheRawTurnBatch("", selected, 0L, 0L, tokens, chars);
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
        for (DubheRawTurn turn : group.turns()) {
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
    private Set<String> uncoveredTurnIds(Deque<DubheRawTurn> turns, String coveredThroughTurnId) {
        Set<String> uncovered = new LinkedHashSet<>();
        boolean afterCursor = coveredThroughTurnId == null || coveredThroughTurnId.isBlank();
        for (DubheRawTurn turn : turns) {
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
    private Set<String> keepWindowTurnIds(DubheScope scope) {
        Set<String> ids = new LinkedHashSet<>();
        for (DubheRawTurn turn : recent(scope)) {
            ids.add(turn.id());
        }
        return ids;
    }

    /**
     * Whether the whole prompt would reach the watermark: the summary block and the permanent tail are paid
     * alongside the raw window, so the trigger counts all three (G35).
     */
    public boolean promptWatermarkReached(DubheScope scope) {
        DubheMemoryWindowPolicy activePolicy = policy;
        int watermark = activePolicy.compressionWatermarkTokenBudget();
        if (watermark <= 0) {
            return false;
        }
        int tail = activePolicy.tailRetrievalTokenBudget()
                + activePolicy.tailCurrentInputTokenBudget()
                + TAIL_STATE_TOKENS;
        return rawTokenCount(scope) + activePolicy.summaryBlockTokenBudget() + tail >= watermark;
    }

    /** The current-facts snapshot inside the tail; a size, not a ratio (see the policy layout). */
    private static final int TAIL_STATE_TOKENS = 256;

    /** 窗口当前保留的 raw token 总数（观测与硬上限用，不是换代判据）。 */
    public int rawTokenCount(DubheScope scope) {
        if (!writable(scope)) {
            return 0;
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return 0;
        }
        List<DubheRawTurn> snapshot;
        synchronized (turns) {
            snapshot = new ArrayList<>(turns);
        }
        int tokens = snapshot.stream().mapToInt(DubheRawTurn::tokenCount).sum();
        if (tokens > 0) {
            return tokens;
        }
        return policy.tokensForCharacters(snapshot.stream().mapToInt(DubheRawTurn::characterCount).sum());
    }

    /** 窗口当前生效的策略（只读）。 */
    public DubheMemoryWindowPolicy policy() {
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
    public int dropCompressedTurns(DubheScope scope, String coveredThroughTurnId) {
        return dropCompressedTurns(scope, coveredThroughTurnId, Set.of());
    }

    /**
     * Same drop, plus whole units the alignment decided to keep verbatim. Their summaries stay pending, so the
     * summary block only ever holds units whose raw left the window (G36).
     */
    public int dropCompressedTurns(DubheScope scope, String coveredThroughTurnId, Set<String> forcedKeepTurnIds) {
        if (!writable(scope) || coveredThroughTurnId == null || coveredThroughTurnId.isBlank()) {
            return 0;
        }
        Deque<DubheRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return 0;
        }
        Set<String> keep = new LinkedHashSet<>(keepWindowTurnIds(scope));
        if (forcedKeepTurnIds != null) {
            keep.addAll(forcedKeepTurnIds);
        }
        synchronized (turns) {
            List<DubheRawTurn> snapshot = new ArrayList<>(turns);
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
            List<DubheRawTurn> remaining = new ArrayList<>(snapshot.size());
            int removed = 0;
            for (int index = 0; index < snapshot.size(); index++) {
                DubheRawTurn turn = snapshot.get(index);
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

    /** 单轮是否可以随单元摘要一起移出窗口。事件与对话一样，摘要覆盖之后就可以移出。 */
    private boolean compressibleTurn(DubheRawTurn turn) {
        return turn != null && !turn.isEmpty();
    }


    /** 压缩窗口是否已经装满（发生过容量硬裁剪，或总量已顶到上限）。 */
    public boolean capacityReached(DubheScope scope) {
        if (!writable(scope)) {
            return false;
        }
        DubheMemoryWindowPolicy activePolicy = policy;
        return capacityEvictedWorlds.contains(scope.worldId())
                || (activePolicy.maxRawTokenCount() > 0 && rawTokenCount(scope) >= activePolicy.maxRawTokenCount());
    }

    /** 换代后清掉"装满"信号，下一代重新判定。 */
    public void clearCapacityReached(DubheScope scope) {
        if (scope != null) {
            capacityEvictedWorlds.remove(scope.worldId());
        }
    }

    private void trim(DubheScope scope, Deque<DubheRawTurn> turns) {
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
        DubheMemoryWindowPolicy activePolicy = policy;
        return tokens > activePolicy.maxRawTokenCount() || characters > activePolicy.maxRawCharacters();
    }

    private boolean writable(DubheScope scope) {
        return scope != null && scope.writable();
    }

    private List<TurnGroup> turnGroups(List<DubheRawTurn> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        List<TurnGroup> groups = new ArrayList<>();
        int index = 0;
        while (index < snapshot.size()) {
            DubheRawTurn turn = snapshot.get(index);
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
                DubheRawTurn candidate = snapshot.get(cursor);
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
            List<DubheRawTurn> groupTurns = new ArrayList<>();
            for (int cursor = index; cursor <= end; cursor++) {
                DubheRawTurn candidate = snapshot.get(cursor);
                if (candidate != null && !candidate.isEmpty()) {
                    groupTurns.add(candidate);
                }
            }
            groups.add(new TurnGroup(groupTurns));
            index = end + 1;
        }
        return groups;
    }

    private String dialogueBoundaryKey(DubheRawTurn turn) {
        if (turn == null || turn.iaTurnId() == null || turn.iaTurnId().isBlank()) {
            return "";
        }
        if (!"user".equals(turn.role()) && !"assistant".equals(turn.role())) {
            return "";
        }
        return turn.iaSessionId() + ":" + turn.iaTurnId();
    }

    private record TurnGroup(List<DubheRawTurn> turns) {
        private TurnGroup {
            turns = turns == null ? List.of() : turns.stream()
                    .filter(turn -> turn != null && !turn.isEmpty())
                    .toList();
        }

        private int tokenCount() {
            return turns.stream().mapToInt(DubheRawTurn::tokenCount).sum();
        }

        private int characterCount() {
            return turns.stream().mapToInt(DubheRawTurn::characterCount).sum();
        }

        private int compressibleTokens() {
            return compressibleTurns().stream().mapToInt(DubheRawTurn::tokenCount).sum();
        }

        private int compressibleCharacters() {
            return compressibleTurns().stream().mapToInt(DubheRawTurn::characterCount).sum();
        }

        private List<DubheRawTurn> compressibleTurns() {
            return turns;
        }
    }
}
