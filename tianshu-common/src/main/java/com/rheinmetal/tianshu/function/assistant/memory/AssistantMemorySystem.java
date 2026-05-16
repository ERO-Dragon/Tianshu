package com.rheinmetal.tianshu.function.assistant.memory;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.assistant.output.MemoryUpdateCandidate;
import com.rheinmetal.tianshu.function.assistant.context.AssistantMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.assistant.output.MemoryUpdateTarget;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantJsonStore;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantStorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AssistantMemorySystem {
    private final AssistantStorageLayout layout;
    private final AssistantJsonStore jsonStore;
    private final ConversationWindowStore conversationWindowStore;
    private final ConversationWindowSelector conversationWindowSelector;
    private final ShortTermCompressionPlanner shortTermCompressionPlanner;
    private final ShortTermMemoryBlockStore shortTermMemoryBlockStore;
    private final LongTermMergePlanner longTermMergePlanner;
    private final AssistantCompressionTaskStore compressionTaskStore;
    private final LongTermMemoryRagStore longTermMemoryRagStore;

    public AssistantMemorySystem(AssistantStorageLayout layout, AssistantJsonStore jsonStore) {
        this(layout, jsonStore, null);
    }

    public AssistantMemorySystem(AssistantStorageLayout layout, AssistantJsonStore jsonStore, AssistantMemoryWindowPolicy policy) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.conversationWindowStore = new ConversationWindowStore(layout, jsonStore, policy);
        this.conversationWindowSelector = new ConversationWindowSelector(policy);
        this.shortTermCompressionPlanner = new ShortTermCompressionPlanner(policy);
        this.shortTermMemoryBlockStore = new ShortTermMemoryBlockStore(layout, jsonStore, policy);
        this.longTermMergePlanner = new LongTermMergePlanner(policy);
        this.compressionTaskStore = new AssistantCompressionTaskStore(layout, jsonStore);
        this.longTermMemoryRagStore = new LongTermMemoryRagStore(layout, jsonStore);
    }

    public AssistantMemorySnapshot load(AssistantScope scope) {
        String persona = loadPersona();
        List<String> longTerm = loadTextLines(layout.sharedRoot().resolve("long_term_user_memory.jsonl"));
        if (scope == null || !scope.writable()) {
            return new AssistantMemorySnapshot(persona, longTerm, List.of(), List.of());
        }
        List<String> summary = loadSummary(scope);
        List<ConversationTurn> shortTerm = conversationWindowSelector.selectRecentRawTurns(conversationWindowStore.loadRawTurns(scope));
        List<ShortTermMemoryBlock> blocks = shortTermMemoryBlockStore.load(scope);
        return new AssistantMemorySnapshot(persona, longTerm, summary, blocks, shortTerm);
    }

    public void appendConversationTurn(AssistantScope scope, ConversationTurn turn) {
        if (scope == null || !scope.writable() || turn == null || turn.isEmpty()) {
            return;
        }
        conversationWindowStore.appendRawTurn(scope, turn);
    }

    public void appendMemoryUpdateCandidates(AssistantScope scope, List<MemoryUpdateCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        appendSharedLongTermCandidates(candidates.stream()
                .filter(candidate -> candidate.target() == MemoryUpdateTarget.LONG_TERM_USER_MEMORY)
                .toList());
        appendWorldSummaryCandidates(scope, candidates.stream()
                .filter(candidate -> candidate.target() == MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY)
                .toList());
    }

    public MemoryConsolidationPlan consolidatePendingMemory(AssistantScope scope, MemoryConsolidationPlanner planner) {
        MemoryConsolidationPlanner effectivePlanner = planner == null ? new MemoryConsolidationPlanner() : planner;
        List<MemoryUpdateCandidate> pending = new ArrayList<>();
        pending.addAll(loadPendingLongTermCandidates());
        pending.addAll(loadPendingWorldSummaryCandidates(scope));
        MemoryConsolidationPlan plan = effectivePlanner.plan(pending, load(scope));
        applyConsolidationPlan(scope, plan);
        return plan;
    }

    private void applyConsolidationPlan(AssistantScope scope, MemoryConsolidationPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        appendLongTermMemory(plan.acceptedFor(MemoryUpdateTarget.LONG_TERM_USER_MEMORY).stream().map(MemoryUpdateCandidate::text).toList());
        longTermMemoryRagStore.append(scope, plan.acceptedFor(MemoryUpdateTarget.LONG_TERM_USER_MEMORY).stream().map(MemoryUpdateCandidate::text).toList());
        appendConversationSummary(scope, plan.acceptedFor(MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY).stream().map(MemoryUpdateCandidate::text).toList());
        writePendingLongTermCandidates(plan.deferred().stream().filter(candidate -> candidate.target() == MemoryUpdateTarget.LONG_TERM_USER_MEMORY).toList());
        writePendingWorldSummaryCandidates(scope, plan.deferred().stream().filter(candidate -> candidate.target() == MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY).toList());
    }

    private void appendSharedLongTermCandidates(List<MemoryUpdateCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Path path = layout.sharedRoot().resolve("pending_long_term_user_memory.jsonl");
        List<JsonObject> objects = new ArrayList<>(jsonStore.readJsonLines(path));
        candidates.stream().filter(candidate -> !candidate.isEmpty()).map(this::candidateToJson).forEach(objects::add);
        jsonStore.writeJsonLines(path, objects);
    }

    private void appendWorldSummaryCandidates(AssistantScope scope, List<MemoryUpdateCandidate> candidates) {
        if (scope == null || !scope.writable() || candidates == null || candidates.isEmpty()) {
            return;
        }
        Path path = layout.worldRoot(scope).resolve("pending_conversation_summary.jsonl");
        List<JsonObject> objects = new ArrayList<>(jsonStore.readJsonLines(path));
        candidates.stream().filter(candidate -> !candidate.isEmpty()).map(this::candidateToJson).forEach(objects::add);
        jsonStore.writeJsonLines(path, objects);
    }

    private List<MemoryUpdateCandidate> loadPendingLongTermCandidates() {
        return jsonStore.readJsonLines(layout.sharedRoot().resolve("pending_long_term_user_memory.jsonl")).stream()
                .map(this::candidateFromJson)
                .filter(candidate -> !candidate.isEmpty())
                .toList();
    }

    private List<MemoryUpdateCandidate> loadPendingWorldSummaryCandidates(AssistantScope scope) {
        if (scope == null || !scope.writable()) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.worldRoot(scope).resolve("pending_conversation_summary.jsonl")).stream()
                .map(this::candidateFromJson)
                .filter(candidate -> !candidate.isEmpty())
                .toList();
    }

    private void writePendingLongTermCandidates(List<MemoryUpdateCandidate> candidates) {
        List<JsonObject> objects = candidates == null ? List.of() : candidates.stream().filter(candidate -> !candidate.isEmpty()).map(this::candidateToJson).toList();
        jsonStore.writeJsonLines(layout.sharedRoot().resolve("pending_long_term_user_memory.jsonl"), objects);
    }

    private void writePendingWorldSummaryCandidates(AssistantScope scope, List<MemoryUpdateCandidate> candidates) {
        if (scope == null || !scope.writable()) {
            return;
        }
        List<JsonObject> objects = candidates == null ? List.of() : candidates.stream().filter(candidate -> !candidate.isEmpty()).map(this::candidateToJson).toList();
        jsonStore.writeJsonLines(layout.worldRoot(scope).resolve("pending_conversation_summary.jsonl"), objects);
    }

    private void appendLongTermMemory(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Path path = layout.sharedRoot().resolve("long_term_user_memory.jsonl");
        Set<String> merged = new LinkedHashSet<>(loadTextLines(path));
        entries.stream().filter(entry -> entry != null && !entry.isBlank()).map(String::trim).forEach(merged::add);
        jsonStore.writeJsonLines(path, merged.stream().map(text -> textToJson(text, System.currentTimeMillis())).toList());
    }

    public AssistantCompressionTask planNextCompressionTask(AssistantScope scope) {
        if (scope == null || !scope.writable()) {
            return AssistantCompressionTask.empty();
        }
        long now = System.currentTimeMillis();
        return compressionTaskStore.firstReady(scope, now)
                .orElseGet(() -> compressionTaskStore.firstActive(scope).isPresent()
                        ? AssistantCompressionTask.empty()
                        : longTermMergePlanner.plan(shortTermMemoryBlockStore.load(scope))
                        .map(blocks -> compressionTaskStore.createLongTermTask(scope, blocks))
                        .orElseGet(() -> shortTermCompressionPlanner.plan(conversationWindowStore.loadRawTurns(scope))
                                .map(candidate -> compressionTaskStore.createShortTermTask(scope, candidate))
                                .orElse(AssistantCompressionTask.empty())));
    }

    public AssistantCompressionTask planShortTermCompressionTask(AssistantScope scope) {
        if (scope == null || !scope.writable()) {
            return AssistantCompressionTask.empty();
        }
        return compressionTaskStore.firstActive(scope)
                .orElseGet(() -> shortTermCompressionPlanner.plan(conversationWindowStore.loadRawTurns(scope))
                        .map(candidate -> compressionTaskStore.createShortTermTask(scope, candidate))
                        .orElse(AssistantCompressionTask.empty()));
    }

    public ShortTermCompressionCandidate planShortTermCompression(AssistantScope scope) {
        if (scope == null || !scope.writable()) {
            return new ShortTermCompressionCandidate(List.of(), false, false, 0);
        }
        return shortTermCompressionPlanner.plan(conversationWindowStore.loadRawTurns(scope))
                .orElse(new ShortTermCompressionCandidate(List.of(), false, false, 0));
    }

    public AssistantCompressionTask findCompressionTask(AssistantScope scope, String taskId) {
        if (scope == null || !scope.writable() || taskId == null || taskId.isBlank()) {
            return AssistantCompressionTask.empty();
        }
        return compressionTaskStore.load(scope).stream()
                .filter(task -> task.taskId().equals(taskId))
                .findFirst()
                .orElse(AssistantCompressionTask.empty());
    }

    public void failCompressionTask(AssistantScope scope, AssistantCompressionTask task, String errorCode, String errorMessage) {
        if (task != null && !task.isEmpty()) {
            compressionTaskStore.save(scope, task.fail(errorCode, errorMessage));
        }
    }

    public void markCompressionTaskSubmitted(AssistantScope scope, AssistantCompressionTask task) {
        if (task != null && !task.isEmpty()) {
            compressionTaskStore.save(scope, task.submitted());
        }
    }

    public void suspendCompressionTask(AssistantScope scope, AssistantCompressionTask task) {
        suspendCompressionTask(scope, task, "TASK_SUSPENDED", "Compression task is suspended");
    }

    public void suspendCompressionTask(AssistantScope scope, AssistantCompressionTask task, String code, String message) {
        if (task != null && !task.isEmpty() && !task.terminal()) {
            compressionTaskStore.save(scope, task.suspended(code, message, nextAttemptAt(task.retryCount())));
        }
    }

    private long nextAttemptAt(int retryCount) {
        long delay = Math.min(300000L, 5000L * (1L << Math.min(6, Math.max(0, retryCount))));
        return System.currentTimeMillis() + delay;
    }

    public void acceptShortTermCompression(AssistantScope scope, ShortTermMemoryBlock block, int consumedTurnCount) {
        if (scope == null || !scope.writable() || block == null || block.isEmpty()) {
            return;
        }
        AssistantCompressionTask task = compressionTaskStore.firstActive(scope).orElse(AssistantCompressionTask.empty());
        shortTermMemoryBlockStore.append(scope, block);
        conversationWindowStore.removePrefix(scope, consumedTurnCount);
        if (!task.isEmpty()) {
            compressionTaskStore.save(scope, task.complete(block.content()));
        }
    }

    public void acceptLongTermCompression(AssistantScope scope, AssistantCompressionTask task, String longTermMemory) {
        if (scope == null || !scope.writable() || task == null || task.isEmpty() || longTermMemory == null || longTermMemory.isBlank()) {
            return;
        }
        longTermMemoryRagStore.appendOne(scope, longTermMemory);
        shortTermMemoryBlockStore.removePrefix(scope, Math.max(1, task.sourceTurnCount()));
        compressionTaskStore.save(scope, task.complete(longTermMemory));
    }

    public void recordLongTermMemoryHits(AssistantScope scope, List<String> uids) {
        longTermMemoryRagStore.recordHits(scope, uids);
    }

    public void cleanupLongTermMemory(AssistantScope scope) {
        longTermMemoryRagStore.cleanup(scope);
    }

    public boolean hasMemoryRagEntries(AssistantScope scope) {
        return longTermMemoryRagStore.hasEntries(scope);
    }

    private void appendConversationSummary(AssistantScope scope, List<String> entries) {
        if (scope == null || !scope.writable() || entries == null || entries.isEmpty()) {
            return;
        }
        List<String> merged = new ArrayList<>(loadSummary(scope));
        entries.stream().filter(entry -> entry != null && !entry.isBlank()).map(String::trim).forEach(merged::add);
        JsonObject root = new JsonObject();
        root.addProperty("summary", String.join("\n", merged));
        root.addProperty("updatedAt", System.currentTimeMillis());
        jsonStore.writeObject(layout.worldRoot(scope).resolve("conversation_summary.json"), root);
    }

    private String loadPersona() {
        Path path = layout.sharedRoot().resolve("persona.json");
        return jsonStore.readObject(path)
                .map(json -> json.has("persona") ? json.get("persona").getAsString() : "")
                .filter(value -> !value.isBlank())
                .orElse(AssistantMemorySnapshot.defaultPersona(AssistantPromptLanguage.ZH_CN));
    }

    private List<String> loadTextLines(Path path) {
        return jsonStore.readJsonLines(path).stream()
                .map(json -> json.has("text") ? json.get("text").getAsString() : "")
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private List<String> loadSummary(AssistantScope scope) {
        Path path = layout.worldRoot(scope).resolve("conversation_summary.json");
        return jsonStore.readObject(path)
                .map(json -> json.has("summary") ? json.get("summary").getAsString() : "")
                .filter(value -> !value.isBlank())
                .map(List::of)
                .orElse(List.of());
    }

    private JsonObject candidateToJson(MemoryUpdateCandidate candidate) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("target", candidate.target().name());
        json.addProperty("text", candidate.text());
        json.addProperty("source", candidate.source());
        json.addProperty("confidence", candidate.confidence());
        json.addProperty("requestKey", candidate.requestKey());
        json.addProperty("createdAt", candidate.createdAt());
        return json;
    }

    private MemoryUpdateCandidate candidateFromJson(JsonObject json) {
        MemoryUpdateTarget target = readTarget(json);
        String text = readString(json, "text");
        String source = readString(json, "source");
        int confidence = json.has("confidence") ? json.get("confidence").getAsInt() : 0;
        String requestKey = readString(json, "requestKey");
        long createdAt = json.has("createdAt") ? json.get("createdAt").getAsLong() : System.currentTimeMillis();
        return new MemoryUpdateCandidate(target, text, source, confidence, requestKey, createdAt);
    }

    private MemoryUpdateTarget readTarget(JsonObject json) {
        String value = readString(json, "target");
        try {
            return value.isBlank() ? MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY : MemoryUpdateTarget.valueOf(value);
        } catch (IllegalArgumentException e) {
            return MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY;
        }
    }

    private JsonObject textToJson(String text, long createdAt) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("text", text == null ? "" : text.trim());
        json.addProperty("createdAt", createdAt <= 0L ? System.currentTimeMillis() : createdAt);
        return json;
    }

    private String readString(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }
}
