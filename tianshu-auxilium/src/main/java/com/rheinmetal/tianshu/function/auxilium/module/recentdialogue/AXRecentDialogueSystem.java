package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXTokenCounter;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AXRecentDialogueSystem {
    private final AXRawTurnWindow rawTurnWindow;
    private final AXTokenCounter tokenCounter;
    private final AXRawTurnCheckpointStore checkpointStore;
    private final Set<String> restoredWorlds = ConcurrentHashMap.newKeySet();
    private final Map<String, AXScope> scopesByWorld = new ConcurrentHashMap<>();

    /**
     * 当前代次（按世界），与摘要块账本同步。
     *
     * <p>代次是缓存归因的唯一依据：换代是唯一被预期的一次前缀全 miss，其余轮次的前缀逐字节不变。
     * 持久化在账本里（{@code summary_block.jsonl}），这里只是会话内的可读镜像——每次换代由账本值写入，
     * 不自行累加，避免出现两份互相矛盾的代次。</p>
     */
    private final Map<String, Integer> generationByWorld = new ConcurrentHashMap<>();
    private volatile String activeWorldId = "";

    public AXRecentDialogueSystem(AXMemoryWindowPolicy policy) {
        this(policy, AXTokenCounter.unavailable(), null);
    }

    public AXRecentDialogueSystem(AXMemoryWindowPolicy policy, AXTokenCounter tokenCounter) {
        this(policy, tokenCounter, null);
    }

    public AXRecentDialogueSystem(AXMemoryWindowPolicy policy, AXTokenCounter tokenCounter, AXRawTurnCheckpointStore checkpointStore) {
        this.rawTurnWindow = new AXRawTurnWindow(policy);
        this.tokenCounter = tokenCounter == null ? AXTokenCounter.unavailable() : tokenCounter;
        this.checkpointStore = checkpointStore;
    }

    /**
     * Points the retention window at the per-turn budget.
     *
     * <p>The system is built once, before any turn knows the model's real context size, so its window
     * starts on the construction-time fallback policy. Every resolved turn replaces it through this
     * method; without that the retained history and the compression watermarks would stay frozen at the
     * reference budget while the prompt side already assumed the real one.</p>
     */
    public void applyWindowPolicy(AXMemoryWindowPolicy policy) {
        rawTurnWindow.applyPolicy(policy);
    }

    public void append(AXScope scope, AXRawTurn turn) {
        restoreIfNeeded(scope);
        rawTurnWindow.append(scope, withTokenCount(scope, turn));
    }

    public AXRecentDialogueSnapshot snapshot(AXScope scope) {
        restoreIfNeeded(scope);
        return new AXRecentDialogueSnapshot(rawTurnWindow.recent(scope));
    }

    /**
     * 选出下一个压缩单元。
     *
     * @param coveredThroughTurnId 已有单元摘要覆盖到的时间上界；这些轮次的原文仍留在窗口里，
     *                             只是不再参与压缩（换代时才移出）
     */
    public AXRawTurnBatch selectCompressionBatch(AXScope scope, String coveredThroughTurnId) {
        restoreIfNeeded(scope);
        return rawTurnWindow.selectCompressionBatch(scope, coveredThroughTurnId);
    }

    /**
     * 压缩窗口是否装满：raw 窗口当前 token 数达到 {@code MAX_RAW_TOKENS}（= KEEP_MAX + COMPRESS_MAX）。
     *
     * <p>注意这里<b>不是</b>提示词水位 {@code compressionWatermarkTokenBudget}：那个数描述的是
     * 整份 prompt（system + 摘要块 + raw + 尾部）的上限，与 raw 自己的容量是两把尺子。</p>
     */
    /** 窗口当前保留的 raw token 总数：换代判据与观测都用它。 */
    public int rawTokenCount(AXScope scope) {
        return rawTurnWindow.rawTokenCount(scope);
    }

    public boolean shouldSwapGeneration(AXScope scope) {
        return rawTurnWindow.capacityReached(scope);
    }

    /** 当前世界的压缩代次；换代时推进，用于区分"正常轮次"与"换代引发的全 miss"。 */
    public int generation(AXScope scope) {
        if (scope == null) {
            return 0;
        }
        return generationByWorld.getOrDefault(worldKey(scope), 0);
    }

    /**
     * 摘要缺失时的例外：该单元没有单元块可锚定"覆盖到哪一轮"，只能当场把这段已被覆盖的 raw 移出。
     *
     * <p>不这样做，覆盖游标（{@code max(sourceToMillis)}）不会前进，同一段原文会被反复压成新的
     * 事件——那既不是幂等也不是去重，而是纯粹的重复成本。正常路径（有摘要）<b>依旧不动原文</b>，
     * 只有换代才移出。</p>
     *
     * @return 实际移出的轮次数
     */
    public int dropCoveredRaw(AXScope scope, String coveredThroughTurnId) {
        if (scope == null) {
            return 0;
        }
        int removed = rawTurnWindow.dropCompressedTurns(scope, coveredThroughTurnId);
        rawTurnWindow.clearCapacityReached(scope);
        if (removed > 0) {
            checkpoint(scope);
        }
        return removed;
    }

    /**
     * 换代：把压缩窗口里"已被摘要覆盖"的 raw 移出，并把代次推进到账本给的值。
     *
     * <p>这是 raw <b>唯一</b>被移出的时刻：单元压缩只写摘要、不碰原文。保持窗口（最近
     * {@code KEEP} 轮逐字原文）不在移出范围内——它永远逐字保留。</p>
     *
     * <p>代次的权威副本在摘要块账本里（append-only，重启可重放）；这里写入的是会话内镜像，
     * 因此只接受"不小于当前值"的代次，绝不自行累加。</p>
     *
     * @param generation              账本给出的新代次
     * @param coveredThroughTurnId    已被单元摘要覆盖到的时间上界（也只删到这个边界为止）
     * @return 实际移出窗口的 raw 轮次数
     */
    public int swapGeneration(AXScope scope, int generation, String coveredThroughTurnId) {
        if (scope == null) {
            return 0;
        }
        int removed = rawTurnWindow.dropCompressedTurns(scope, coveredThroughTurnId);
        rawTurnWindow.clearCapacityReached(scope);
        if (generation > 0) {
            generationByWorld.merge(worldKey(scope), generation, Math::max);
        }
        if (removed > 0) {
            checkpoint(scope);
        }
        return removed;
    }

    private static String worldKey(AXScope scope) {
        String worldId = scope.worldId();
        return worldId == null || worldId.isBlank() ? "" : worldId;
    }

    public void checkpoint(AXScope scope) {
        rememberScope(scope);
        if (checkpointStore == null || scope == null || !scope.writable()) {
            return;
        }
        checkpointStore.write(scope, rawTurnWindow.snapshot(scope));
    }

    public void checkpointAll() {
        if (checkpointStore == null) {
            return;
        }
        for (Map.Entry<String, List<AXRawTurn>> entry : rawTurnWindow.snapshotAll().entrySet()) {
            AXScope scope = scopesByWorld.get(entry.getKey());
            if (scope != null && scope.writable()) {
                checkpointStore.write(scope, entry.getValue());
            }
        }
    }

    private void restoreIfNeeded(AXScope scope) {
        rememberScope(scope);
        if (checkpointStore == null || scope == null || !scope.writable()) {
            return;
        }
        if (restoredWorlds.add(scope.worldId())) {
            List<AXRawTurn> restored = checkpointStore.load(scope);
            if (!restored.isEmpty()) {
                rawTurnWindow.replace(scope, restored);
            }
        }
    }

    private synchronized void rememberScope(AXScope scope) {
        if (scope != null && scope.writable()) {
            String previousWorldId = activeWorldId;
            if (!previousWorldId.isBlank() && !previousWorldId.equals(scope.worldId())) {
                AXScope previousScope = scopesByWorld.get(previousWorldId);
                if (previousScope != null && checkpointStore != null) {
                    checkpointStore.write(previousScope, rawTurnWindow.snapshot(previousScope));
                }
            }
            scopesByWorld.put(scope.worldId(), scope);
            activeWorldId = scope.worldId();
        }
    }

    private AXRawTurn withTokenCount(AXScope scope, AXRawTurn turn) {
        if (turn == null || turn.isEmpty() || turn.tokenCount() > 0) {
            return turn;
        }
        OptionalInt count = tokenCounter.countMessageTokens(requestId(scope, turn), tokenizerRole(turn), tokenizerContent(turn));
        return count.isPresent() ? turn.withTokenCount(count.getAsInt()) : turn;
    }

    private String requestId(AXScope scope, AXRawTurn turn) {
        String worldId = scope == null || scope.worldId() == null || scope.worldId().isBlank() ? turn.worldId() : scope.worldId();
        return "ax.recent_dialogue.token." + worldId + "." + turn.id();
    }

    private String tokenizerRole(AXRawTurn turn) {
        return turn != null && turn.assistantRole() ? "assistant" : "user";
    }

    private String tokenizerContent(AXRawTurn turn) {
        // The same renderer the prompt uses, so the estimate is measured against the wrapper that is actually
        // sent. Only the language connective of a speaker event can be missing here (no vocabulary at this
        // layer), which is a handful of tokens: the estimate stays directional instead of exact.
        return AXRawTurnPromptText.render(turn, null);
    }
}
