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
     * 已发生的压缩换代次数，按世界计。换代意味着"压缩窗口里的一批轮次已被消费"，
     * 提示词前缀从这一刻起必然不同，因此它是缓存观测里唯一被预期的一次全 miss。
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

    public AXRawTurnBatch selectCompressionBatch(AXScope scope) {
        restoreIfNeeded(scope);
        return rawTurnWindow.selectCompressionBatch(scope);
    }

    public int confirmConsumed(AXScope scope, AXRawTurnBatch batch) {
        restoreIfNeeded(scope);
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        List<String> turnIds = batch.turnIds();
        if (turnIds.isEmpty()) {
            return 0;
        }
        int consumed = rawTurnWindow.confirmConsumed(scope, turnIds);
        if (consumed > 0) {
            generationByWorld.merge(worldKey(scope), 1, Integer::sum);
            checkpoint(scope);
        }
        return consumed;
    }

    /** 当前世界的压缩代次；换代时自增，用于区分"正常轮次"与"换轮引发的全 miss"。 */
    public int generation(AXScope scope) {
        if (scope == null) {
            return 0;
        }
        return generationByWorld.getOrDefault(worldKey(scope), 0);
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
        if (turn == null || turn.content() == null) {
            return "";
        }
        if (turn.gameChatRole()) {
            String speaker = turn.speakerName() == null || turn.speakerName().isBlank() ? "unknown" : turn.speakerName().trim();
            return "<chat speaker=\"" + speaker + "\">" + turn.content().trim() + "</chat>";
        }
        return turn.content().trim();
    }
}
