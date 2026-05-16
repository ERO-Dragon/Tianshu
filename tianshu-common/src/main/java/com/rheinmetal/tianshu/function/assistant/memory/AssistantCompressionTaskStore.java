package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantJsonStore;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantStorageLayout;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class AssistantCompressionTaskStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AssistantStorageLayout layout;
    private final AssistantJsonStore jsonStore;

    public AssistantCompressionTaskStore(AssistantStorageLayout layout, AssistantJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AssistantCompressionTask> load(AssistantScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(file(scope)).stream()
                .map(AssistantCompressionTask::fromJson)
                .filter(task -> !task.isEmpty())
                .sorted(Comparator.comparingLong(AssistantCompressionTask::createdAt))
                .toList();
    }

    public Optional<AssistantCompressionTask> firstActive(AssistantScope scope) {
        return load(scope).stream()
                .filter(task -> !task.terminal())
                .findFirst();
    }

    public Optional<AssistantCompressionTask> firstReady(AssistantScope scope, long now) {
        return load(scope).stream()
                .filter(task -> task.readyToAttempt(now))
                .findFirst();
    }

    public AssistantCompressionTask createShortTermTask(AssistantScope scope, ShortTermCompressionCandidate candidate) {
        if (!writable(scope) || candidate == null || candidate.isEmpty()) {
            return AssistantCompressionTask.empty();
        }
        Optional<AssistantCompressionTask> existing = firstActive(scope);
        if (existing.isPresent()) {
            return existing.get();
        }
        AssistantCompressionTask task = AssistantCompressionTask.fromCandidate(newTaskId(), candidate);
        List<AssistantCompressionTask> tasks = new ArrayList<>(load(scope));
        tasks.add(task);
        write(scope, tasks);
        return task;
    }

    public AssistantCompressionTask createLongTermTask(AssistantScope scope, List<ShortTermMemoryBlock> blocks) {
        if (!writable(scope) || blocks == null || blocks.isEmpty()) {
            return AssistantCompressionTask.empty();
        }
        Optional<AssistantCompressionTask> existing = firstActive(scope);
        if (existing.isPresent()) {
            return existing.get();
        }
        AssistantCompressionTask task = AssistantCompressionTask.fromLongTermBlocks(newTaskId(), blocks);
        List<AssistantCompressionTask> tasks = new ArrayList<>(load(scope));
        tasks.add(task);
        write(scope, tasks);
        return task;
    }

    public void save(AssistantScope scope, AssistantCompressionTask task) {
        if (!writable(scope) || task == null || task.isEmpty()) {
            return;
        }
        List<AssistantCompressionTask> tasks = new ArrayList<>();
        boolean replaced = false;
        for (AssistantCompressionTask existing : load(scope)) {
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

    private void write(AssistantScope scope, List<AssistantCompressionTask> tasks) {
        List<AssistantCompressionTask> normalized = tasks == null ? List.of() : tasks.stream()
                .filter(task -> task != null && !task.isEmpty())
                .toList();
        jsonStore.writeJsonLines(file(scope), normalized.stream().map(AssistantCompressionTask::toJson).toList());
    }

    private Path file(AssistantScope scope) {
        return layout.worldRoot(scope).resolve("compression_tasks.jsonl");
    }

    private boolean writable(AssistantScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }

    private String newTaskId() {
        return "assistant-compress-" + Long.toUnsignedString(System.currentTimeMillis(), 36) + "-" + Long.toUnsignedString(RANDOM.nextLong(), 36);
    }
}
