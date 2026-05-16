package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.context.AssistantMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantJsonStore;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantStorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConversationWindowStore {
    private final AssistantStorageLayout layout;
    private final AssistantJsonStore jsonStore;
    private final AssistantMemoryWindowPolicy policy;

    public ConversationWindowStore(AssistantStorageLayout layout, AssistantJsonStore jsonStore, AssistantMemoryWindowPolicy policy) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.policy = policy == null ? AssistantMemoryWindowPolicy.DEFAULT : policy;
    }

    public List<ConversationTurn> loadRawTurns(AssistantScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(rawTurnsFile(scope)).stream()
                .map(ConversationTurn::fromJson)
                .filter(turn -> !turn.isEmpty())
                .toList();
    }

    public void appendRawTurn(AssistantScope scope, ConversationTurn turn) {
        if (!writable(scope) || turn == null || turn.isEmpty()) {
            return;
        }
        List<ConversationTurn> turns = new ArrayList<>(loadRawTurns(scope));
        turns.add(turn);
        writeRawTurns(scope, trimToRawLimits(turns));
    }

    public void writeRawTurns(AssistantScope scope, List<ConversationTurn> turns) {
        if (!writable(scope)) {
            return;
        }
        List<ConversationTurn> normalized = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        jsonStore.writeJsonLines(rawTurnsFile(scope), normalized.stream().map(ConversationTurn::toJson).toList());
    }

    public void removePrefix(AssistantScope scope, int count) {
        if (!writable(scope) || count <= 0) {
            return;
        }
        List<ConversationTurn> turns = new ArrayList<>(loadRawTurns(scope));
        if (turns.isEmpty()) {
            return;
        }
        int from = Math.min(count, turns.size());
        writeRawTurns(scope, turns.subList(from, turns.size()));
    }

    private List<ConversationTurn> trimToRawLimits(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        int tokens = turns.stream().mapToInt(ConversationTurn::estimatedTokens).sum();
        int chars = turns.stream().mapToInt(ConversationTurn::characterCount).sum();
        int from = 0;
        while (from < turns.size() && (tokens > policy.maxRawEstimatedTokens() || chars > policy.maxRawCharacters())) {
            ConversationTurn removed = turns.get(from++);
            tokens -= removed.estimatedTokens();
            chars -= removed.characterCount();
        }
        return List.copyOf(turns.subList(from, turns.size()));
    }

    private Path rawTurnsFile(AssistantScope scope) {
        return layout.worldRoot(scope).resolve("conversation_raw_turns.jsonl");
    }

    private boolean writable(AssistantScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
