package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import java.util.List;

public record AXMemoryRetrievalTrace(
        String stmId,
        double score,
        List<EventHit> eventHits
) {
    public AXMemoryRetrievalTrace {
        stmId = stmId == null ? "" : stmId.trim();
        score = Math.max(0.0D, score);
        eventHits = eventHits == null ? List.of() : eventHits.stream()
                .filter(hit -> hit != null && !hit.isEmpty())
                .toList();
    }

    public boolean isEmpty() {
        return stmId.isBlank() || eventHits.isEmpty();
    }

    public record EventHit(
            String eventId,
            String eventFactHash,
            String effectiveMappingId,
            double relevance
    ) {
        public EventHit {
            eventId = eventId == null ? "" : eventId.trim();
            eventFactHash = eventFactHash == null ? "" : eventFactHash.trim();
            effectiveMappingId = effectiveMappingId == null ? "" : effectiveMappingId.trim();
            relevance = Math.max(0.0D, relevance);
        }

        boolean isEmpty() {
            return eventId.isBlank() || relevance <= 0.0D;
        }
    }
}
