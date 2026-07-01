package com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.List;

public final class AXStmBlockStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXStmBlockStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXStmBlock> loadAll(AXScope scope) {
        if (!readable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.stmBlocksFile(scope)).stream()
                .map(AXStmBlock::fromJson)
                .filter(block -> !block.isEmpty())
                .toList();
    }

    public List<AXStmBlock> loadRecent(AXScope scope, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AXStmBlock> blocks = loadAll(scope);
        int from = Math.max(0, blocks.size() - limit);
        return List.copyOf(blocks.subList(from, blocks.size()));
    }

    public void append(AXScope scope, AXStmBlock block) {
        if (!readable(scope) || block == null || block.isEmpty()) {
            return;
        }
        List<AXStmBlock> existing = loadAll(scope);
        if (existing.stream().anyMatch(value -> duplicate(value, block))) {
            return;
        }
        AXStmBlock normalized = block;
        if (normalized.previousStmId().isBlank() && !existing.isEmpty()) {
            AXStmBlock previous = existing.get(existing.size() - 1);
            normalized = new AXStmBlock(
                    normalized.id(),
                    normalized.contentHash(),
                    normalized.worldId(),
                    normalized.createdAtMillis(),
                    normalized.sourceFromMillis(),
                    normalized.sourceToMillis(),
                    previous.id(),
                    normalized.nextStmId(),
                    normalized.sourceTurnCount(),
                    normalized.estimatedTokens(),
                    normalized.content(),
                    normalized.attachedEventIds()
            );
        }
        jsonStore.appendJsonLine(layout.stmBlocksFile(scope), normalized.toJson());
    }

    private boolean duplicate(AXStmBlock left, AXStmBlock right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.id().equals(right.id())) {
            return true;
        }
        return left.sourceFromMillis() == right.sourceFromMillis()
                && left.sourceToMillis() == right.sourceToMillis()
                && left.contentHash().equals(right.contentHash());
    }

    public void rewrite(AXScope scope, List<AXStmBlock> blocks) {
        if (!readable(scope)) {
            return;
        }
        List<AXStmBlock> normalized = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
        jsonStore.writeJsonLines(layout.stmBlocksFile(scope), normalized.stream().map(AXStmBlock::toJson).toList());
    }

    private boolean readable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
