package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXRawTurnWindowTest {
    private final AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
    private long clock = 1_000L;

    @Test
    void recentWindowKeepsCompleteDialogueRoundBoundary() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(25, 20, 1000, 1000, 10000));
        appendRound(window, "u1", "a1", "turn-1", 10);
        appendRound(window, "u2", "a2", "turn-2", 10);

        List<AXRawTurn> recent = window.recent(scope);

        assertEquals(List.of("u2", "a2"), recent.stream().map(AXRawTurn::id).toList());
    }

    @Test
    void interleavedGameChatStaysInsideDialogueRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(25, 20, 1000, 1000, 10000));
        window.append(scope, turn("u1", "user", "帮我看附近", "turn-1", 10));
        window.append(scope, chatEvent("g1", "Steve", "附近有村庄吗？", 5));
        window.append(scope, turn("a1", "assistant", "我会结合聊天判断", "turn-1", 10));

        List<AXRawTurn> recent = window.recent(scope);

        assertEquals(List.of("u1", "g1", "a1"), recent.stream().map(AXRawTurn::id).toList());
    }

    @Test
    void compressionUnitTakesTheOldestRoundsOutsideTheKeptWindow() {
        // 保持窗口（最近一轮）永不压缩；压缩单元只从它前面的原文里攒。
        AXRawTurnWindow window = new AXRawTurnWindow(policy(30, 20, 10, 20, 10000, 15));
        appendRound(window, "u1", "a1", "turn-1", 10);
        appendRound(window, "u2", "a2", "turn-2", 10);

        AXRawTurnBatch batch = window.selectCompressionBatch(scope, "");

        assertEquals(List.of("u1", "a1"), batch.turnIds(), "最旧的一轮进单元，保持窗口那一轮不动");
    }

    @Test
    void compressionWaitsUntilAFullUnitIsAvailable() {
        // 单元容量 15 token，可压部分只有 10 token：攒不够一个单元就不压，避免压出半步单元。
        AXRawTurnWindow window = new AXRawTurnWindow(policy(40, 20, 10, 20, 10000, 15));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));
        window.append(scope, turn("a2", "assistant", "第二答", "turn-2", 10));

        assertEquals(List.of(), window.selectCompressionBatch(scope, "").turnIds());
    }

    @Test
    void coveredTurnsAreSkippedInsteadOfConsumed() {
        // 单元压缩不移出原文：已经覆盖的轮次只是不再参与选批。
        AXRawTurnWindow window = new AXRawTurnWindow(policy(30, 20, 10, 20, 10000, 15));
        appendRound(window, "u1", "a1", "turn-1", 10);
        appendRound(window, "u2", "a2", "turn-2", 10);

        AXRawTurnBatch first = window.selectCompressionBatch(scope, "");
        AXRawTurnBatch repeated = window.selectCompressionBatch(scope, "");
        AXRawTurnBatch afterCovered = window.selectCompressionBatch(scope, "a1");

        assertEquals(first.turnIds(), repeated.turnIds(), "再选一次还是同一批：原文没有被消费");
        assertEquals(4, window.snapshot(scope).size(), "窗口里的原文一份都没少");
        assertEquals(List.of(), afterCovered.turnIds(), "覆盖游标之前的轮次不再参与选批");
    }

    @Test
    void swapDropsCoveredRawButKeepsTheKeptWindow() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(30, 20, 10, 20, 10000, 15));
        appendRound(window, "u1", "a1", "turn-1", 10);
        appendRound(window, "u2", "a2", "turn-2", 10);

        int removed = window.dropCompressedTurns(scope, "a1");

        assertEquals(2, removed);
        assertEquals(List.of("u2", "a2"), window.snapshot(scope).stream().map(AXRawTurn::id).toList(),
                "保持窗口那两轮必须留下");
    }

    @Test
    void worldEventsAreNotDroppedByTheGenerationSwap() {
        // 世界事件不进压缩单元，因此换代裁剪时也必须原样留下。
        AXRawTurnWindow window = new AXRawTurnWindow(policy(30, 20, 10, 20, 10000, 15));
        appendRound(window, "u1", "a1", "turn-1", 10);
        window.append(scope, worldEvent("w1", "玩家死亡一次。", 5));
        appendRound(window, "u2", "a2", "turn-2", 10);

        int removed = window.dropCompressedTurns(scope, "w1");

        assertEquals(2, removed, "只移出被覆盖的两轮对话");
        assertEquals(List.of("w1", "u2", "a2"), window.snapshot(scope).stream().map(AXRawTurn::id).toList());
    }

    @Test
    void recentWindowRejectsOversizedRoundInsteadOfSplittingIt() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(15, 10, 1000, 1000, 10000));
        appendRound(window, "u1", "a1", "turn-1", 10);

        assertEquals(List.of(), window.recent(scope));
    }

    @Test
    void hardCapacityTrimRemovesWholeOldestRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1, 1, 1, 1, 25));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));

        assertEquals(List.of("u2"), window.snapshot(scope).stream().map(AXRawTurn::id).toList());
    }

    @Test
    void hardCapacityTrimDoesNotSplitNewestRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1, 1, 1, 1, 15));
        appendRound(window, "u1", "a1", "turn-1", 10);

        assertEquals(List.of("u1", "a1"), window.snapshot(scope).stream().map(AXRawTurn::id).toList());
    }

    @Test
    void hardCapacityTrimKeepsGameChatWithInProgressDialogueRound() {
        AXRawTurnWindow window = new AXRawTurnWindow(policy(1, 1, 1, 1, 12));
        window.append(scope, turn("u1", "user", "第一问", "turn-1", 10));
        window.append(scope, turn("a1", "assistant", "第一答", "turn-1", 10));
        window.append(scope, turn("u2", "user", "第二问", "turn-2", 10));
        window.append(scope, chatEvent("g2", "Steve", "补充信息", 5));

        assertEquals(List.of("u2", "g2"), window.snapshot(scope).stream().map(AXRawTurn::id).toList());
    }

    private void appendRound(AXRawTurnWindow window, String userId, String assistantId, String turnId, int tokens) {
        window.append(scope, turn(userId, "user", "问" + userId, turnId, tokens));
        window.append(scope, turn(assistantId, "assistant", "答" + assistantId, turnId, tokens));
    }

    private AXRawTurn turn(String id, String role, String content, String turnId, int tokens) {
        return new AXRawTurn(id, role, content, clock++, scope.worldId(), "session", turnId, tokens, content.length(), "");
    }

    private AXRawTurn chatEvent(String id, String speaker, String content, int tokens) {
        return new AXRawTurn(id, "world_event", content, clock++, scope.worldId(), "", "presence.chat", tokens, content.length(), "", speaker);
    }

    private AXRawTurn worldEvent(String id, String content, int tokens) {
        return new AXRawTurn(id, "world_event", content, clock++, scope.worldId(), "", "presence.event", tokens, content.length(), "", "");
    }

    private AXMemoryWindowPolicy policy(
            int keepMax,
            int keepTarget,
            int compressTarget,
            int compressMax,
            int maxRawTokens
    ) {
        return policy(keepMax, keepTarget, compressTarget, compressMax, maxRawTokens, 8_192);
    }

    private AXMemoryWindowPolicy policy(
            int keepMax,
            int keepTarget,
            int compressTarget,
            int compressMax,
            int maxRawTokens,
            int compressionUnitTokens
    ) {
        // This harness drives the kept-window triple, the unit size and the raw capacity only; the
        // summary block, the watermark and the tail are carried along inert.
        return new AXMemoryWindowPolicy(
                1000,
                1000,
                0,
                1000,
                0,
                125,
                750,
                400,
                keepTarget,
                keepMax,
                compressTarget,
                compressMax,
                compressionUnitTokens,
                14_000,
                512,
                128,
                maxRawTokens,
                10000,
                3,
                60000L
        );
    }
}
