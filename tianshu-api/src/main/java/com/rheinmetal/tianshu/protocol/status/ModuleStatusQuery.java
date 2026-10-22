package com.rheinmetal.tianshu.protocol.status;

public record ModuleStatusQuery(
        String moduleId,
        String statusType
) {
    public ModuleStatusQuery {
        moduleId = moduleId == null ? "" : moduleId.trim();
        statusType = statusType == null ? "" : statusType.trim();
    }

    public boolean hasModuleFilter() {
        return !moduleId.isBlank();
    }

    public boolean hasTypeFilter() {
        return !statusType.isBlank();
    }
}
