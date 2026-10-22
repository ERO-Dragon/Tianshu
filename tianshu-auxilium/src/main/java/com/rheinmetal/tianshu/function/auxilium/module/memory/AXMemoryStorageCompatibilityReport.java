package com.rheinmetal.tianshu.function.auxilium.module.memory;

import java.util.List;

public record AXMemoryStorageCompatibilityReport(
        boolean manifestPresent,
        boolean compatible,
        List<Issue> issues
) {
    public AXMemoryStorageCompatibilityReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
        compatible = compatible && issues.stream().noneMatch(Issue::error);
    }

    public static AXMemoryStorageCompatibilityReport missingManifest() {
        return new AXMemoryStorageCompatibilityReport(false, true, List.of(
                Issue.warning("AX_MEMORY_MANIFEST_MISSING", "manifest will be created before durable memory writes")
        ));
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(Issue::error);
    }

    public List<String> errorCodes() {
        return issues.stream()
                .filter(Issue::error)
                .map(Issue::code)
                .toList();
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public record Issue(Severity severity, String code, String detail) {
        public Issue {
            severity = severity == null ? Severity.WARNING : severity;
            code = code == null || code.isBlank() ? "AX_MEMORY_STORAGE_COMPATIBILITY" : code.trim();
            detail = detail == null ? "" : detail.trim();
        }

        static Issue warning(String code, String detail) {
            return new Issue(Severity.WARNING, code, detail);
        }

        static Issue error(String code, String detail) {
            return new Issue(Severity.ERROR, code, detail);
        }

        boolean error() {
            return severity == Severity.ERROR;
        }
    }
}
