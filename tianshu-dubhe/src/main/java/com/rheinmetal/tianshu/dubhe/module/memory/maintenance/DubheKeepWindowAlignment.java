package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aligns the verbatim keep window to compression-unit boundaries at reset time (G36).
 *
 * <p>The keep budget is a token range, and a unit is a contiguous turn range. Cutting a unit in half would leave the
 * summary block describing turns whose raw text is still in the prompt — the same history twice. So a unit whose
 * turns are <b>at least half</b> inside the keep budget is kept whole and its summary stays pending; every unit
 * before it is evicted and promoted.</p>
 *
 * <p>Pure function over the window snapshot, the keep ids and the pending units, so the rule is testable without
 * storage or a model.</p>
 */
public final class DubheKeepWindowAlignment {

    private DubheKeepWindowAlignment() {
    }

    /**
     * @param promotableStmIds unit summaries whose raw leaves the window; only these enter the summary block
     * @param dropThroughTurnId drop raw up to this turn, or blank when nothing may be dropped
     * @param forcedKeepTurnIds turns of units kept whole; reset must not cut them even when they look covered
     */
    public record Decision(
            Set<String> promotableStmIds,
            String dropThroughTurnId,
            Set<String> forcedKeepTurnIds
    ) {

        public Decision {
            promotableStmIds = promotableStmIds == null ? Set.of() : Set.copyOf(promotableStmIds);
            dropThroughTurnId = dropThroughTurnId == null ? "" : dropThroughTurnId.trim();
            forcedKeepTurnIds = forcedKeepTurnIds == null ? Set.of() : Set.copyOf(forcedKeepTurnIds);
        }
    }

    public static Decision decide(
            List<DubheStmBlock> pendingOldestFirst,
            List<DubheRawTurn> windowTurns,
            Set<String> keepTurnIds,
            String coveredThroughTurnId
    ) {
        List<DubheRawTurn> window = windowTurns == null ? List.of() : windowTurns;
        Set<String> keep = keepTurnIds == null ? Set.of() : keepTurnIds;
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int position = 0; position < window.size(); position++) {
            index.put(window.get(position).id(), position);
        }

        Set<String> promotable = new LinkedHashSet<>();
        Set<String> forcedKeep = new LinkedHashSet<>();
        String dropThrough = coveredThroughTurnId == null ? "" : coveredThroughTurnId.trim();

        List<DubheStmBlock> units = pendingOldestFirst == null ? List.of() : pendingOldestFirst;
        boolean keptBoundaryReached = false;
        for (DubheStmBlock unit : units) {
            Range range = rangeOf(unit, window, index);
            if (range == null) {
                // Its raw is already gone: the summary is the only copy left, so it must be promoted.
                promotable.add(unit.id());
                continue;
            }
            if (keptBoundaryReached) {
                // Newer units sit inside the kept region by construction.
                forcedKeep.addAll(idsOf(window, range));
                continue;
            }
            int keptCount = 0;
            for (int position = range.from(); position <= range.to(); position++) {
                if (keep.contains(window.get(position).id())) {
                    keptCount++;
                }
            }
            boolean majorityKept = keptCount * 2 >= (range.to() - range.from() + 1);
            if (!majorityKept) {
                promotable.add(unit.id());
                continue;
            }
            forcedKeep.addAll(idsOf(window, range));
            keptBoundaryReached = true;
            dropThrough = range.from() > 0 ? window.get(range.from() - 1).id() : "";
        }

        if (!keptBoundaryReached) {
            // Nothing stays verbatim: every pending unit is evicted, and the covered cursor bounds the drop.
            forcedKeep.clear();
        }
        return new Decision(promotable, dropThrough, forcedKeep);
    }

    private static Range rangeOf(DubheStmBlock unit, List<DubheRawTurn> window, Map<String, Integer> index) {
        int from = index.getOrDefault(unit.sourceFromTurnId(), -1);
        int to = index.getOrDefault(unit.sourceToTurnId(), -1);
        if (to < 0) {
            // The end is already outside the window; nothing of this unit remains when the start is gone too.
            if (from < 0) {
                return null;
            }
            to = window.size() - 1;
        }
        if (from < 0) {
            from = 0;
        }
        return to < from ? null : new Range(from, to);
    }

    private static List<String> idsOf(List<DubheRawTurn> window, Range range) {
        List<String> ids = new ArrayList<>(range.to() - range.from() + 1);
        for (int position = range.from(); position <= range.to(); position++) {
            ids.add(window.get(position).id());
        }
        return ids;
    }

    private record Range(int from, int to) {
    }
}