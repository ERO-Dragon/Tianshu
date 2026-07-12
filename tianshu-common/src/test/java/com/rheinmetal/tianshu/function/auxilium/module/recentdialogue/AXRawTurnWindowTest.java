package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXRawTurnWindowTest {
    private final AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);

    @Test
    void recentWindowKeepsCompleteDialogueRoundBoundary() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(25, 1000, 1100, 1000, 1000, 10000));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));
        window.append(scope, turn("a2", "assistant", "第二答", "turn-2", 10));

        List<AXRawTurn> recent = window.recent(scope);

        assertEquals(List.of("u2", "a2"), recent.stream().map(AXRawTurn::id).toList());
    }

    @Test
    void interleavedGameChatStaysInsideDialogueRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(25, 1000, 1100, 1000, 1000, 10000));
        window.append(scope, turn("u1", "user", "帮我看附近", "turn-1", 10));
        window.append(scope, gameChat("g1", "Steve", "附近有村庄吗？", 5));
        window.append(scope, turn("a1", "assistant", "我会结合聊天判断", "turn-1", 10));

        List<AXRawTurn> recent = window.recent(scope);

        assertEquals(List.of("u1", "g1", "a1"), recent.stream().map(AXRawTurn::id).toList());
    }

    @Test
    void compressionBatchPeelsCompleteOldestDialogueRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1000, 20, 30, 10, 20, 10000));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));
        window.append(scope, turn("a2", "assistant", "第二答", "turn-2", 10));

        AXRawTurnBatch batch = window.selectCompressionBatch(scope);

        assertEquals(List.of("u1", "a1"), batch.turnIds());
    }

    @Test
    void compressionWaitsUntilRawKeepUpperLimitIsExceeded() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1000, 20, 40, 10, 20, 10000));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));

        assertEquals(List.of(), window.selectCompressionBatch(scope).turnIds());
    }

    @Test
    void recentWindowRejectsOversizedRoundInsteadOfSplittingIt() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(15, 1000, 1100, 1000, 1000, 10000));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));

        assertEquals(List.of(), window.recent(scope));
    }

    @Test
    void hardCapacityTrimRemovesWholeOldestRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1000, 1, 1, 1, 1, 25));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));

        assertEquals(List.of("u2"), window.snapshot(scope).stream().map(AXRawTurn::id).toList());
    }

    @Test
    void hardCapacityTrimDoesNotSplitNewestRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1000, 1, 1, 1, 1, 15));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));

        assertEquals(List.of("u1", "a1"), window.snapshot(scope).stream().map(AXRawTurn::id).toList());
    }

    @Test
    void hardCapacityTrimKeepsGameChatWithInProgressDialogueRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1000, 1, 1, 1, 1, 12));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));
        window.append(scope, gameChat("g2", "Steve", "补充信息", 5));

        assertEquals(List.of("u2", "g2"), window.snapshot(scope).stream().map(AXRawTurn::id).toList());
    }

    private AXRawTurn turn(String id, String role, String content, String turnId, int tokens) {
        return new AXRawTurn(id, role, content, System.currentTimeMillis(), scope.worldId(), "session", turnId, tokens, content.length(), "");
    }

    private AXRawTurn gameChat(String id, String speaker, String content, int tokens) {
        return new AXRawTurn(id, "game_chat", content, System.currentTimeMillis(), scope.worldId(), "", "presence.chat", tokens, content.length(), "", speaker);
    }

    private AXMemoryWindowPolicy policy(
            int rawDialogueBudget,
            int keepTarget,
            int keepMax,
            int compressTarget,
            int compressMax,
            int maxRawTokens
    ) {
        return new AXMemoryWindowPolicy(
                1000,
                1000,
                0,
                100,
                300,
                250,
                100,
                rawDialogueBudget,
                50,
                1000,
                0,
                125,
                125,
                750,
                keepTarget,
                keepMax,
                compressTarget,
                compressMax,
                maxRawTokens,
                10000,
                3,
                60000L
        );
    }
}
