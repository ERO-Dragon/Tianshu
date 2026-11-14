package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三因子打分（记忆设计 §6.1/§6.2/§6.3.4）：乘积形态、三个地板、强度 S、钉子与两条阈值线。
 *
 * <p>这些断言全部落在纯函数上：给定事件与 {@code now}，分数必唯一——这是"可回放、可重算"的最小前提。</p>
 */
class AXMemoryScoringTest {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private final AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);

    @Test
    void scoreIsTheProductOfRelevanceRecencyAndImportance() {
        long now = 1_700_000_000_000L;
        AXMemoryEvent fresh = event("玩家把钻石镐放在末影箱里。", 8, now - DAY, List.of("1"));

        double recency = AXMemoryScoring.recency(fresh, now);
        double importance = AXMemoryScoring.importanceFactor(fresh);
        double score = AXMemoryScoring.score(0.72D, fresh, now);

        assertEquals(0.72D * recency * importance, score, 1e-12);
        assertEquals(0.8D, importance, 1e-12, "imp=8 → 0.8");
    }

    @Test
    void higherRelevanceWinsAtEqualRecencyAndImportance() {
        long now = 1_700_000_000_000L;
        AXMemoryEvent event = event("玩家在平原村庄东侧安家。", 6, now - DAY, List.of("1"));

        assertTrue(AXMemoryScoring.score(0.90D, event, now) > AXMemoryScoring.score(0.60D, event, now));
    }

    @Test
    void higherImportanceWinsAtEqualRelevanceAndRecency() {
        long now = 1_700_000_000_000L;
        AXMemoryEvent important = event("玩家不喜欢吃腐肉。", 9, now - DAY, List.of("1"));
        AXMemoryEvent ordinary = event("玩家吃掉了一块面包。", 3, now - DAY, List.of("1"));

        assertTrue(AXMemoryScoring.score(0.70D, important, now) > AXMemoryScoring.score(0.70D, ordinary, now));
    }

    @Test
    void newerEventWinsAtEqualRelevanceAndImportance() {
        long now = 1_700_000_000_000L;
        AXMemoryEvent recent = event("玩家刚刚发现了远古残骸。", 6, now - HOUR, List.of("1"));
        AXMemoryEvent old = event("玩家很久以前发现了远古残骸。", 6, now - 30 * DAY, List.of("1"));

        assertTrue(AXMemoryScoring.score(0.70D, recent, now) > AXMemoryScoring.score(0.70D, old, now));
    }

    @Test
    void recencyDecaysExponentiallyAndStopsAtTheFloor() {
        long now = 1_700_000_000_000L;
        AXMemoryEvent event = event("玩家在 128, 64, -302 建造了传送门。", 5, now - 7 * DAY, List.of("1"));

        // S=1 时半衰期 168 小时 = 7 天：一周后应落在地板与 1 之间的中点附近（0.525）。
        assertEquals(0.525D, AXMemoryScoring.recency(event, now), 0.01D);
        // 一千天后必贴住 0.05 地板，不会归零。
        assertEquals(AXMemoryScoring.RECENCY_FLOOR, AXMemoryScoring.recency(event, now + 1_000 * DAY), 1e-12);
        // 1000 天与 2000 天的分数完全相等：地板之下没有更低的档。
        assertEquals(AXMemoryScoring.recency(event, now + 1_000 * DAY),
                AXMemoryScoring.recency(event, now + 2_000 * DAY), 1e-12);
    }

    @Test
    void repeatedMentionsExtendTheHalfLifeUpToEight() {
        long now = 1_700_000_000_000L;
        AXMemoryEvent single = event("玩家提到过一次的事。", 6, now - 30 * DAY, List.of("1"));
        AXMemoryEvent repeated = event("玩家在窗口里反复提到的事。", 6, now - 30 * DAY,
                List.of("1", "2", "3", "4", "5", "6"));
        AXMemoryEvent many = event("被提到非常多次的事。", 6, now - 30 * DAY,
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16"));

        assertEquals(1.0D, AXMemoryScoring.supportStrength(single), 1e-12);
        assertEquals(3.5D, AXMemoryScoring.supportStrength(repeated), 1e-12);
        assertEquals(8.0D, AXMemoryScoring.supportStrength(many), 1e-12, "S 上限 8");
        assertTrue(AXMemoryScoring.recency(repeated, now) > AXMemoryScoring.recency(single, now),
                "复述越多衰减越慢");
    }

    @Test
    void importanceFactorNeverReachesZero() {
        assertEquals(AXMemoryScoring.IMPORTANCE_FLOOR, AXMemoryScoring.importanceFactor(event("最弱的事件。", 1, 1L, List.of())), 1e-12);
        assertEquals(1.0D, AXMemoryScoring.importanceFactor(event("最强的事件。", 10, 1L, List.of())), 1e-12);
        assertTrue(AXMemoryScoring.importanceFactor(null) > 0.0D, "缺事件也要给一个非零乘数");
    }

    @Test
    void pinnedImportanceKeepsTheRaisedRecencyFloor() {
        long now = 1_700_000_000_000L;
        AXMemoryEvent pinned = event("玩家的家在平原村庄东侧。", AXMemoryScoring.PINNED_IMPORTANCE, now - 400 * DAY, List.of("1"));
        AXMemoryEvent ordinary = event("玩家随手整理了一下箱子。", AXMemoryScoring.PINNED_IMPORTANCE - 1, now - 400 * DAY, List.of("1"));

        assertEquals(AXMemoryScoring.PINNED_RECENCY_FLOOR, AXMemoryScoring.recency(pinned, now), 1e-12,
                "重要性 ≥ 7 的记忆不被时间打进遗忘区");
        assertEquals(AXMemoryScoring.RECENCY_FLOOR, AXMemoryScoring.recency(ordinary, now), 1e-12);
        assertTrue(AXMemoryScoring.score(0.60D, pinned, now) > AXMemoryScoring.score(0.60D, ordinary, now));
    }

    @Test
    void forcedHotNeedsBothHighImportanceAndHighRelevance() {
        AXMemoryEvent important = event("玩家在 128, 64, -302 建造了传送门。", AXMemoryScoring.FORCED_HOT_IMPORTANCE, 1L, List.of());

        assertTrue(AXMemoryScoring.isForcedHot(important, AXMemoryScoring.FORCED_HOT_RELEVANCE));
        assertFalse(AXMemoryScoring.isForcedHot(important, AXMemoryScoring.FORCED_HOT_RELEVANCE - 0.01D),
                "相关性不够就不走特权通道");
        AXMemoryEvent ordinary = event("玩家获得了一个成就。", AXMemoryScoring.FORCED_HOT_IMPORTANCE - 1, 1L, List.of());
        assertFalse(AXMemoryScoring.isForcedHot(ordinary, 1.0D), "重要性不够也不走");
    }

    @Test
    void coldAdmissionNeedsImportanceOfFour() {
        assertTrue(AXMemoryScoring.isColdEligible(event("玩家的等级从 12 升到 30。", AXMemoryScoring.COLD_MIN_IMPORTANCE, 1L, List.of())));
        assertFalse(AXMemoryScoring.isColdEligible(event("玩家问附魔台怎么用。", AXMemoryScoring.COLD_MIN_IMPORTANCE - 1, 1L, List.of())),
                "弱事实不进 Cold，避免长期配额被闲聊占住");
    }

    private AXMemoryEvent event(String fact, int importance, long happenedAtMillis, List<String> turnRefs) {
        return new AXMemoryEvent("", fact, "", "stm_test", "stm_fact", scope.worldId(), "", "", false,
                happenedAtMillis, happenedAtMillis, List.of(), turnRefs, importance);
    }
}
