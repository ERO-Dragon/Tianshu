package com.rheinmetal.tianshu.dubhe.module.memory.shortterm;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;

import java.util.List;

public final class DubheStmBlockStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheStmBlockStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<DubheStmBlock> loadAll(DubheScope scope) {
        if (!readable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.stmBlocksFile(scope)).stream()
                .map(DubheStmBlock::fromJson)
                .filter(block -> !block.isEmpty())
                .toList();
    }

    public List<DubheStmBlock> loadRecent(DubheScope scope, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<DubheStmBlock> blocks = loadAll(scope);
        int from = Math.max(0, blocks.size() - limit);
        return List.copyOf(blocks.subList(from, blocks.size()));
    }

    public boolean append(DubheScope scope, DubheStmBlock block) {
        if (!readable(scope) || block == null || block.isEmpty()) {
            return false;
        }
        List<DubheStmBlock> existing = loadAll(scope);
        if (existing.stream().anyMatch(value -> duplicate(value, block))) {
            return true;
        }
        DubheStmBlock normalized = block;
        if (normalized.previousStmId().isBlank() && !existing.isEmpty()) {
            DubheStmBlock previous = existing.get(existing.size() - 1);
            normalized = new DubheStmBlock(
                    normalized.id(),
                    normalized.contentHash(),
                    normalized.worldId(),
                    normalized.createdAtMillis(),
                    normalized.sourceFromMillis(),
                    normalized.sourceToMillis(),
                    previous.id(),
                    normalized.nextStmId(),
                    normalized.sourceTurnCount(),
                    normalized.tokenCount(),
                    normalized.content(),
                    normalized.attachedEventIds(),
                    normalized.sourceFromTurnId(),
                    normalized.sourceToTurnId()
            );
        }
        return jsonStore.tryAppendJsonLine(layout.stmBlocksFile(scope), normalized.toJson());
    }

    private boolean duplicate(DubheStmBlock left, DubheStmBlock right) {
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

    public void rewrite(DubheScope scope, List<DubheStmBlock> blocks) {
        if (!readable(scope)) {
            return;
        }
        List<DubheStmBlock> normalized = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
        jsonStore.writeJsonLines(layout.stmBlocksFile(scope), normalized.stream().map(DubheStmBlock::toJson).toList());
    }

    private boolean readable(DubheScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
