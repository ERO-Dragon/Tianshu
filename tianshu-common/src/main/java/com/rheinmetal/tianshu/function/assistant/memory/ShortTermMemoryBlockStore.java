package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.context.AssistantMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantJsonStore;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantStorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ShortTermMemoryBlockStore {
    private final AssistantStorageLayout layout;
    private final AssistantJsonStore jsonStore;
    private final int keepCount;

    public ShortTermMemoryBlockStore(AssistantStorageLayout layout, AssistantJsonStore jsonStore, AssistantMemoryWindowPolicy policy) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.keepCount = Math.max(8, (policy == null ? AssistantMemoryWindowPolicy.DEFAULT : policy).shortTermChatBlockLimit() * 4);
    }

    public List<ShortTermMemoryBlock> load(AssistantScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(file(scope)).stream()
                .map(ShortTermMemoryBlock::fromJson)
                .filter(block -> !block.isEmpty())
                .toList();
    }

    public void append(AssistantScope scope, ShortTermMemoryBlock block) {
        if (!writable(scope) || block == null || block.isEmpty()) {
            return;
        }
        List<ShortTermMemoryBlock> blocks = new ArrayList<>(load(scope));
        blocks.add(block);
        writeTrimmed(scope, blocks);
    }

    public void removePrefix(AssistantScope scope, int count) {
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

    private void writeTrimmed(AssistantScope scope, List<ShortTermMemoryBlock> blocks) {
        List<ShortTermMemoryBlock> normalized = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
        int from = Math.max(0, normalized.size() - keepCount);
        write(scope, normalized.subList(from, normalized.size()));
    }

    private void write(AssistantScope scope, List<ShortTermMemoryBlock> blocks) {
        List<ShortTermMemoryBlock> normalized = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
        jsonStore.writeJsonLines(file(scope), normalized.stream().map(ShortTermMemoryBlock::toJson).toList());
    }

    private Path file(AssistantScope scope) {
        return layout.worldRoot(scope).resolve("short_term_memory_blocks.jsonl");
    }

    private boolean writable(AssistantScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
