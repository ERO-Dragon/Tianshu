package com.rheinmetal.tianshu.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only model availability information prepared by a module model service.
 */
public record ModelAvailabilitySnapshot(
        Map<String, Entry> entries,
        boolean ready,
        long updatedAtMillis
) {
    public ModelAvailabilitySnapshot {
        Map<String, Entry> normalized = new LinkedHashMap<>();
        if (entries != null) {
            entries.forEach((key, entry) -> {
                if (key != null && !key.isBlank() && entry != null) {
                    normalized.put(key, entry);
                }
            });
        }
        entries = Map.copyOf(normalized);
        updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public static ModelAvailabilitySnapshot empty() {
        return new ModelAvailabilitySnapshot(Map.of(), false, 0L);
    }

    public Entry entry(String key) {
        return key == null ? null : entries.get(key);
    }

    public record Entry(boolean installed, long sizeBytes) {
        public Entry {
            sizeBytes = Math.max(0L, sizeBytes);
        }
    }
}
