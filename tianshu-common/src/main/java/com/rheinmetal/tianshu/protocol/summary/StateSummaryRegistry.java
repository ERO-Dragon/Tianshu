package com.rheinmetal.tianshu.protocol.summary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class StateSummaryRegistry {
    private final Map<StateSummaryKey, StateSummary> summaries = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<StateSummary>> listeners = new CopyOnWriteArrayList<>();

    public synchronized void submit(StateSummary summary) {
        StateSummaryKey key = StateSummaryKey.from(summary);
        summaries.put(key, summary);
        listeners.forEach(listener -> listener.accept(summary));
    }

    public synchronized Optional<StateSummary> latest(String moduleId, String summaryType) {
        StateSummaryKey key = new StateSummaryKey(moduleId, summaryType);
        StateSummary summary = summaries.get(key);
        if (summary == null || summary.expired(System.currentTimeMillis())) {
            summaries.remove(key);
            return Optional.empty();
        }
        return Optional.of(summary);
    }

    public synchronized List<StateSummary> byModule(String moduleId) {
        return filter(moduleId, null);
    }

    public synchronized List<StateSummary> byType(String summaryType) {
        return filter(null, summaryType);
    }

    public synchronized List<StateSummary> all() {
        purgeExpired();
        return List.copyOf(summaries.values());
    }

    public void addListener(Consumer<StateSummary> listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(Consumer<StateSummary> listener) {
        if (listener != null) listeners.remove(listener);
    }

    private List<StateSummary> filter(String moduleId, String summaryType) {
        purgeExpired();
        List<StateSummary> result = new ArrayList<>();
        for (StateSummary summary : summaries.values()) {
            if (moduleId != null && !moduleId.isBlank() && !summary.moduleId().equals(moduleId.trim())) continue;
            if (summaryType != null && !summaryType.isBlank() && !summary.summaryType().equals(summaryType.trim())) continue;
            result.add(summary);
        }
        return List.copyOf(result);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        summaries.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    private record StateSummaryKey(String moduleId, String summaryType) {
        StateSummaryKey {
            moduleId = moduleId == null ? "" : moduleId.trim();
            summaryType = summaryType == null ? "" : summaryType.trim();
        }

        static StateSummaryKey from(StateSummary summary) {
            return new StateSummaryKey(summary.moduleId(), summary.summaryType());
        }
    }
}
