package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.List;

public record AXRawTurnBatch(
        String batchId,
        List<AXRawTurn> turns,
        long sourceFromMillis,
        long sourceToMillis,
        int tokenCount,
        int characterCount
) {
    public AXRawTurnBatch {
        turns = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        long from = sourceFromMillis;
        long to = sourceToMillis;
        int tokens = Math.max(0, tokenCount);
        int chars = Math.max(0, characterCount);
        if (!turns.isEmpty()) {
            from = turns.stream().mapToLong(AXRawTurn::createdAtMillis).min().orElse(from);
            to = turns.stream().mapToLong(AXRawTurn::createdAtMillis).max().orElse(to);
            if (tokens <= 0) {
                tokens = turns.stream().mapToInt(AXRawTurn::tokenCount).sum();
            }
            if (chars <= 0) {
                chars = turns.stream().mapToInt(AXRawTurn::characterCount).sum();
            }
        }
        sourceFromMillis = Math.max(0L, from);
        sourceToMillis = Math.max(sourceFromMillis, to);
        tokenCount = tokens;
        characterCount = chars;
        batchId = batchId == null || batchId.isBlank()
                ? buildBatchId(turns, sourceFromMillis, sourceToMillis)
                : batchId.trim();
    }

    public static AXRawTurnBatch empty() {
        return new AXRawTurnBatch("", List.of(), 0L, 0L, 0, 0);
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }

    public List<String> turnIds() {
        return turns.stream().map(AXRawTurn::id).toList();
    }

    public String sourceHash() {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder source = new StringBuilder()
                .append(sourceFromMillis).append('\n')
                .append(sourceToMillis).append('\n');
        for (AXRawTurn turn : turns) {
            source.append(turn.id()).append('\n')
                    .append(turn.role()).append('\n')
                    .append(turn.createdAtMillis()).append('\n')
                    .append(turn.contentHash()).append('\n');
        }
        return AXHashing.sha256Short(source.toString());
    }

    public String stmId() {
        String hash = sourceHash();
        return hash.isBlank() ? "" : "stm_" + hash;
    }

    private static String buildBatchId(List<AXRawTurn> turns, long from, long to) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        String first = turns.get(0).id();
        String last = turns.get(turns.size() - 1).id();
        return "raw_batch_" + Long.toUnsignedString(from, 36) + "_" + Long.toUnsignedString(to, 36) + "_" + Integer.toUnsignedString((first + last).hashCode(), 36);
    }
}
