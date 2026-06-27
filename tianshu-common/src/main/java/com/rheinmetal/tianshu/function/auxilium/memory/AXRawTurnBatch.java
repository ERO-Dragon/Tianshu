package com.rheinmetal.tianshu.function.auxilium.memory;

import java.util.List;

public record AXRawTurnBatch(
        String batchId,
        List<AXRawTurn> turns,
        long sourceFromMillis,
        long sourceToMillis,
        int estimatedTokens,
        int characterCount
) {
    public AXRawTurnBatch {
        turns = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        long from = sourceFromMillis;
        long to = sourceToMillis;
        int tokens = Math.max(0, estimatedTokens);
        int chars = Math.max(0, characterCount);
        if (!turns.isEmpty()) {
            from = turns.stream().mapToLong(AXRawTurn::createdAtMillis).min().orElse(from);
            to = turns.stream().mapToLong(AXRawTurn::createdAtMillis).max().orElse(to);
            if (tokens <= 0) {
                tokens = turns.stream().mapToInt(AXRawTurn::estimatedTokens).sum();
            }
            if (chars <= 0) {
                chars = turns.stream().mapToInt(AXRawTurn::characterCount).sum();
            }
        }
        sourceFromMillis = Math.max(0L, from);
        sourceToMillis = Math.max(sourceFromMillis, to);
        estimatedTokens = tokens;
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

    private static String buildBatchId(List<AXRawTurn> turns, long from, long to) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        String first = turns.get(0).id();
        String last = turns.get(turns.size() - 1).id();
        return "raw_batch_" + Long.toUnsignedString(from, 36) + "_" + Long.toUnsignedString(to, 36) + "_" + Integer.toUnsignedString((first + last).hashCode(), 36);
    }
}
