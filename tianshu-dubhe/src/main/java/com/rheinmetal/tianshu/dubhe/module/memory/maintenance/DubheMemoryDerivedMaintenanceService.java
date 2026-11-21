package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemoryStatsSnapshot;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySystem;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.memory.summary.DubheMemorySummaryBlock;

public final class DubheMemoryDerivedMaintenanceService {
    private final DubheMemorySystem memorySystem;

    public DubheMemoryDerivedMaintenanceService(DubheMemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    public DubheMemoryDerivedMaintenanceResult maintain(DubheScope scope) {
        if (scope == null || !scope.writable() || memorySystem == null) {
            return DubheMemoryDerivedMaintenanceResult.skipped();
        }
        boolean rewritten = normalizeStmChain(scope);
        DubheMemoryStatsSnapshot stats = rebuildStats(scope);
        memorySystem.stats().write(scope, stats);
        return new DubheMemoryDerivedMaintenanceResult(true, rewritten, stats);
    }

    private boolean normalizeStmChain(DubheScope scope) {
        List<DubheStmBlock> loaded = memorySystem.stmBlocks().loadAll(scope);
        if (loaded.isEmpty()) {
            return false;
        }
        Map<String, DubheStmBlock> unique = new LinkedHashMap<>();
        for (DubheStmBlock block : loaded) {
            unique.putIfAbsent(block.id(), block);
        }
        List<DubheStmBlock> blocks = new ArrayList<>(unique.values());
        boolean changed = blocks.size() != loaded.size();
        List<DubheStmBlock> normalized = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            DubheStmBlock block = blocks.get(index);
            String previous = index == 0 ? "" : blocks.get(index - 1).id();
            String next = index + 1 >= blocks.size() ? "" : blocks.get(index + 1).id();
            if (!previous.equals(block.previousStmId()) || !next.equals(block.nextStmId())) {
                changed = true;
                normalized.add(new DubheStmBlock(
                        block.id(),
                        block.contentHash(),
                        block.worldId(),
                        block.createdAtMillis(),
                        block.sourceFromMillis(),
                        block.sourceToMillis(),
                        previous,
                        next,
                        block.sourceTurnCount(),
                        block.tokenCount(),
                        block.content(),
                        block.attachedEventIds(),
                        block.sourceFromTurnId(),
                        block.sourceToTurnId()
                ));
            } else {
                normalized.add(block);
            }
        }
        if (changed) {
            memorySystem.stmBlocks().rewrite(scope, normalized);
        }
        return changed;
    }

    private DubheMemoryStatsSnapshot rebuildStats(DubheScope scope) {
        List<DubheStmBlock> blocks = memorySystem.stmBlocks().loadAll(scope);
        List<DubheMemoryEvent> events = memorySystem.events().loadAll(scope);
        int stmTokens = blocks.stream().mapToInt(DubheStmBlock::tokenCount).sum();
        long earliest = events.stream().mapToLong(DubheMemoryEvent::happenedAtMillis).filter(value -> value > 0L).min().orElse(0L);
        long latest = events.stream().mapToLong(DubheMemoryEvent::happenedAtMillis).filter(value -> value > 0L).max().orElse(earliest);
        int vectorCount = memorySystem.vectors().loadAllNamespaces(scope).size();
        DubheMemorySummaryBlock summaryBlock = memorySystem.summaryBlock(scope);
        return new DubheMemoryStatsSnapshot(
                DubheMemoryStatsSnapshot.SCHEMA_VERSION,
                scope.worldId(),
                System.currentTimeMillis(),
                blocks.size(),
                events.size(),
                vectorCount,
                stmTokens,
                earliest,
                latest,
                summaryBlock.generation(),
                summaryBlock.summariesInBlock(),
                summaryBlock.droppedTotal(),
                summaryBlock.summaryBlockTokens()
        );
    }
}
