package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ShortTermMemoryBlockStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;
    private final int keepCount;

    public ShortTermMemoryBlockStore(AXStorageLayout layout, AXJsonStore jsonStore, AXMemoryWindowPolicy policy) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.keepCount = Math.max(8, (policy == null ? AXMemoryWindowPolicy.DEFAULT : policy).shortTermChatBlockLimit() * 4);
    }

    public List<ShortTermMemoryBlock> load(AXScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(file(scope)).stream()
                .map(ShortTermMemoryBlock::fromJson)
                .filter(block -> !block.isEmpty())
                .toList();
    }

    public void append(AXScope scope, ShortTermMemoryBlock block) {
        if (!writable(scope) || block == null || block.isEmpty()) {
            return;
        }
        List<ShortTermMemoryBlock> blocks = new ArrayList<>(load(scope));
        blocks.add(block);
        writeTrimmed(scope, blocks);
    }

    public void removePrefix(AXScope scope, int count) {
        if (!writable(scope) || count <= 0) {
            return;
        }
        List<ShortTermMemoryBlock> blocks = new ArrayList<>(load(scope));
        if (blocks.isEmpty()) {
            return;
        }
        int from = Math.min(count, blocks.size());
        writeTrimmed(scope, blocks.subList(from, blocks.size()));
    }

    private void writeTrimmed(AXScope scope, List<ShortTermMemoryBlock> blocks) {
        List<ShortTermMemoryBlock> normalized = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
        int from = Math.max(0, normalized.size() - keepCount);
        write(scope, normalized.subList(from, normalized.size()));
    }

    private void write(AXScope scope, List<ShortTermMemoryBlock> blocks) {
        List<ShortTermMemoryBlock> normalized = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
        jsonStore.writeJsonLines(file(scope), normalized.stream().map(ShortTermMemoryBlock::toJson).toList());
    }

    private Path file(AXScope scope) {
        return layout.worldRoot(scope).resolve("short_term_memory_blocks.jsonl");
    }

    private boolean writable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
