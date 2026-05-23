package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConversationWindowStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;
    private final AXMemoryWindowPolicy policy;

    public ConversationWindowStore(AXStorageLayout layout, AXJsonStore jsonStore, AXMemoryWindowPolicy policy) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.policy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
    }

    public List<ConversationTurn> loadRawTurns(AXScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(rawTurnsFile(scope)).stream()
                .map(ConversationTurn::fromJson)
                .filter(turn -> !turn.isEmpty())
                .toList();
    }

    public void appendRawTurn(AXScope scope, ConversationTurn turn) {
        if (!writable(scope) || turn == null || turn.isEmpty()) {
            return;
        }
        List<ConversationTurn> turns = new ArrayList<>(loadRawTurns(scope));
        turns.add(turn);
        writeRawTurns(scope, trimToRawLimits(turns));
    }

    public void writeRawTurns(AXScope scope, List<ConversationTurn> turns) {
        if (!writable(scope)) {
            return;
        }
        List<ConversationTurn> normalized = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        jsonStore.writeJsonLines(rawTurnsFile(scope), normalized.stream().map(ConversationTurn::toJson).toList());
    }

    public void removePrefix(AXScope scope, int count) {
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

    private Path rawTurnsFile(AXScope scope) {
        return layout.worldRoot(scope).resolve("conversation_raw_turns.jsonl");
    }

    private boolean writable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
