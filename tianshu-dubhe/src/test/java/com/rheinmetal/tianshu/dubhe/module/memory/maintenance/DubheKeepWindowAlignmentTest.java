package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheKeepWindowAlignmentTest {

    private static final DubheScope SCOPE =
            new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);

    @Test
    void aUnitThatIsMostlyInsideTheKeepWindowStaysWholeAndIsNotPromoted() {
        DubheRawTurn a = turn("a");
        DubheRawTurn b = turn("b");
        DubheRawTurn c = turn("c");
        DubheRawTurn d = turn("d");
        DubheRawTurn e = turn("e");
        DubheRawTurn f = turn("f");
        List<DubheRawTurn> window = List.of(a, b, c, d, e, f);
        List<DubheStmBlock> pending = List.of(
                unit("u1", a.id(), b.id()), unit("u2", c.id(), d.id()), unit("u3", e.id(), f.id()));

        DubheKeepWindowAlignment.Decision decision = DubheKeepWindowAlignment.decide(
                pending, window, Set.of(d.id(), e.id(), f.id()), f.id());

        assertEquals(Set.of("u1"), decision.promotableStmIds(), "only the evicted unit reaches the block");
        assertEquals(b.id(), decision.dropThroughTurnId(), "drop stops before the kept unit");
        assertEquals(Set.of(c.id(), d.id(), e.id(), f.id()), decision.forcedKeepTurnIds(),
                "the kept unit and everything newer stay verbatim");
    }

    @Test
    void whenNothingIsKeptEveryPendingUnitIsPromoted() {
        DubheRawTurn a = turn("a");
        DubheRawTurn b = turn("b");
        DubheRawTurn c = turn("c");
        List<DubheRawTurn> window = List.of(a, b, c);
        List<DubheStmBlock> pending = List.of(unit("u1", a.id(), b.id()));

        DubheKeepWindowAlignment.Decision decision = DubheKeepWindowAlignment.decide(
                pending, window, Set.of(), b.id());

        assertEquals(Set.of("u1"), decision.promotableStmIds());
        assertEquals(b.id(), decision.dropThroughTurnId(), "the covered cursor bounds the drop");
        assertTrue(decision.forcedKeepTurnIds().isEmpty());
    }

    @Test
    void aUnitWhoseRawAlreadyLeftIsPromotedEvenWithoutTurnsInTheWindow() {
        DubheRawTurn c = turn("c");
        DubheRawTurn d = turn("d");
        List<DubheRawTurn> window = List.of(c, d);
        List<DubheStmBlock> pending = List.of(unit("u1", "gone-a", "gone-b"));

        DubheKeepWindowAlignment.Decision decision = DubheKeepWindowAlignment.decide(
                pending, window, Set.of(c.id(), d.id()), d.id());

        assertEquals(Set.of("u1"), decision.promotableStmIds(), "the summary is the only copy left");
        assertTrue(decision.forcedKeepTurnIds().isEmpty());
    }

    @Test
    void aUnitWhollyInsideTheKeepWindowIsNotPromotedAndNothingIsDropped() {
        DubheRawTurn a = turn("a");
        DubheRawTurn b = turn("b");
        List<DubheRawTurn> window = List.of(a, b);
        List<DubheStmBlock> pending = List.of(unit("u1", a.id(), b.id()));

        DubheKeepWindowAlignment.Decision decision = DubheKeepWindowAlignment.decide(
                pending, window, Set.of(a.id(), b.id()), b.id());

        assertTrue(decision.promotableStmIds().isEmpty());
        assertFalse(decision.forcedKeepTurnIds().isEmpty());
        assertEquals("", decision.dropThroughTurnId(), "nothing before the kept unit may be dropped");
    }

    private static DubheRawTurn turn(String content) {
        return DubheRawTurn.dialogue(SCOPE, "user", content, "session-" + content, "turn-" + content);
    }

    private static DubheStmBlock unit(String id, String fromTurnId, String toTurnId) {
        return new DubheStmBlock(id, "", "world", 1_000L, 1_000L, 2_000L, "", "",
                2, 10, "summary", List.of(), fromTurnId, toTurnId);
    }
}