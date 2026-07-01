package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryStatsSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;

public final class AXMemoryDerivedMaintenanceService {
    private final AXMemorySystem memorySystem;

    public AXMemoryDerivedMaintenanceService(AXMemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    public AXMemoryDerivedMaintenanceResult maintain(AXScope scope) {
        if (scope == null || !scope.writable() || memorySystem == null) {
            return AXMemoryDerivedMaintenanceResult.skipped();
        }
        boolean rewritten = normalizeStmChain(scope);
        AXMemoryStatsSnapshot stats = rebuildStats(scope);
        memorySystem.stats().write(scope, stats);
        return new AXMemoryDerivedMaintenanceResult(true, rewritten, stats);
    }

    private boolean normalizeStmChain(AXScope scope) {
        List<AXStmBlock> loaded = memorySystem.stmBlocks().loadAll(scope);
        if (loaded.isEmpty()) {
            return false;
        }
        Map<String, AXStmBlock> unique = new LinkedHashMap<>();
        for (AXStmBlock block : loaded) {
            unique.putIfAbsent(block.id(), block);
        }
        List<AXStmBlock> blocks = new ArrayList<>(unique.values());
        boolean changed = blocks.size() != loaded.size();
        List<AXStmBlock> normalized = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            AXStmBlock block = blocks.get(index);
            String previous = index == 0 ? "" : blocks.get(index - 1).id();
            String next = index + 1 >= blocks.size() ? "" : blocks.get(index + 1).id();
            if (!previous.equals(block.previousStmId()) || !next.equals(block.nextStmId())) {
                changed = true;
                normalized.add(new AXStmBlock(
                        block.id(),
                        block.contentHash(),
                        block.worldId(),
                        block.createdAtMillis(),
                        block.sourceFromMillis(),
                        block.sourceToMillis(),
                        previous,
                        next,
                        block.sourceTurnCount(),
                        block.estimatedTokens(),
                        block.content(),
                        block.attachedEventIds()
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

    private AXMemoryStatsSnapshot rebuildStats(AXScope scope) {
        List<AXStmBlock> blocks = memorySystem.stmBlocks().loadAll(scope);
        List<AXMemoryEvent> events = memorySystem.events().loadAll(scope);
        List<AXAttachedWorldEvent> attached = memorySystem.attachedWorldEvents().loadAll(scope);
        int stmTokens = blocks.stream().mapToInt(AXStmBlock::estimatedTokens).sum();
        int eventTokens = events.stream().mapToInt(AXMemoryEvent::estimatedTokens).sum();
        long earliest = events.stream().mapToLong(AXMemoryEvent::happenedAtMillis).filter(value -> value > 0L).min().orElse(0L);
        long latest = events.stream().mapToLong(AXMemoryEvent::happenedAtMillis).filter(value -> value > 0L).max().orElse(earliest);
        int vectorCount = memorySystem.vectors().loadAllNamespaces(scope).size();
        return new AXMemoryStatsSnapshot(
                AXMemoryStatsSnapshot.SCHEMA_VERSION,
                scope.worldId(),
                System.currentTimeMillis(),
                blocks.size(),
                events.size(),
                attached.size(),
                vectorCount,
                stmTokens,
                eventTokens,
                earliest,
                latest
        );
    }
}
