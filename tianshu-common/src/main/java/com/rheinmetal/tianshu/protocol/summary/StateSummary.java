package com.rheinmetal.tianshu.protocol.summary;

import java.util.List;
import java.util.Map;

public record StateSummary(
        String moduleId,
        String summaryType,
        String title,
        List<String> lines,
        StateSummarySeverity severity,
        long updatedAtMillis,
        long ttlMillis,
        boolean usableByDialogue,
        boolean visibleToGui,
        StateSummaryVisibility visibility,
        Map<String, String> tags,
        String rawJson
) {
    public StateSummary {
        moduleId = requireText(moduleId, "moduleId");
        summaryType = requireText(summaryType, "summaryType");
        title = sanitize(title);
        lines = lines == null || lines.isEmpty()
                ? List.of()
                : lines.stream().filter(line -> line != null && !line.isBlank()).map(String::trim).toList();
        severity = severity == null ? StateSummarySeverity.INFO : severity;
        if (updatedAtMillis <= 0L) updatedAtMillis = System.currentTimeMillis();
        ttlMillis = Math.max(0L, ttlMillis);
        visibility = visibility == null ? StateSummaryVisibility.MODULE : visibility;
        tags = tags == null || tags.isEmpty() ? Map.of() : Map.copyOf(tags);
        rawJson = sanitize(rawJson);
    }

    public boolean expired(long nowMillis) {
        return ttlMillis > 0L && nowMillis > updatedAtMillis + ttlMillis;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
