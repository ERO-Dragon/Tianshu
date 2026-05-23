package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class AXCompressionTaskStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXCompressionTaskStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXCompressionTask> load(AXScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(file(scope)).stream()
                .map(AXCompressionTask::fromJson)
                .filter(task -> !task.isEmpty())
                .sorted(Comparator.comparingLong(AXCompressionTask::createdAt))
                .toList();
    }

    public Optional<AXCompressionTask> firstActive(AXScope scope) {
        return load(scope).stream()
                .filter(task -> !task.terminal())
                .findFirst();
    }

    public Optional<AXCompressionTask> firstReady(AXScope scope, long now) {
        return load(scope).stream()
                .filter(task -> task.readyToAttempt(now))
                .findFirst();
    }

    public AXCompressionTask createShortTermTask(AXScope scope, ShortTermCompressionCandidate candidate) {
        if (!writable(scope) || candidate == null || candidate.isEmpty()) {
            return AXCompressionTask.empty();
        }
        Optional<AXCompressionTask> existing = firstActive(scope);
        if (existing.isPresent()) {
            return existing.get();
        }
        AXCompressionTask task = AXCompressionTask.fromCandidate(newTaskId(), candidate);
        List<AXCompressionTask> tasks = new ArrayList<>(load(scope));
        tasks.add(task);
        write(scope, tasks);
        return task;
    }

    public AXCompressionTask createLongTermTask(AXScope scope, List<ShortTermMemoryBlock> blocks) {
        if (!writable(scope) || blocks == null || blocks.isEmpty()) {
            return AXCompressionTask.empty();
        }
        Optional<AXCompressionTask> existing = firstActive(scope);
        if (existing.isPresent()) {
            return existing.get();
        }
        AXCompressionTask task = AXCompressionTask.fromLongTermBlocks(newTaskId(), blocks);
        List<AXCompressionTask> tasks = new ArrayList<>(load(scope));
        tasks.add(task);
        write(scope, tasks);
        return task;
    }

    public void save(AXScope scope, AXCompressionTask task) {
        if (!writable(scope) || task == null || task.isEmpty()) {
            return;
        }
        List<AXCompressionTask> tasks = new ArrayList<>();
        boolean replaced = false;
        for (AXCompressionTask existing : load(scope)) {
            if (existing.taskId().equals(task.taskId())) {
                tasks.add(task);
                replaced = true;
            } else {
                tasks.add(existing);
            }
        }
        if (!replaced) {
            tasks.add(task);
        }
        write(scope, tasks);
    }

    private void write(AXScope scope, List<AXCompressionTask> tasks) {
        List<AXCompressionTask> normalized = tasks == null ? List.of() : tasks.stream()
                .filter(task -> task != null && !task.isEmpty())
                .toList();
        jsonStore.writeJsonLines(file(scope), normalized.stream().map(AXCompressionTask::toJson).toList());
    }

    private Path file(AXScope scope) {
        return layout.worldRoot(scope).resolve("compression_tasks.jsonl");
    }

    private boolean writable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }

    private String newTaskId() {
        return "AX-compress-" + Long.toUnsignedString(System.currentTimeMillis(), 36) + "-" + Long.toUnsignedString(RANDOM.nextLong(), 36);
    }
}
