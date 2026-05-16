package com.rheinmetal.tianshu.protocol.summary;

public record StateSummaryQuery(
        String moduleId,
        String summaryType,
        boolean includeExpired,
        StateSummaryVisibility minimumVisibility
) {
    public StateSummaryQuery {
        moduleId = moduleId == null ? "" : moduleId.trim();
        summaryType = summaryType == null ? "" : summaryType.trim();
        minimumVisibility = minimumVisibility == null ? StateSummaryVisibility.PRIVATE : minimumVisibility;
    }

    public boolean hasModuleFilter() {
        return !moduleId.isBlank();
    }

    public boolean hasTypeFilter() {
        return !summaryType.isBlank();
    }
}
