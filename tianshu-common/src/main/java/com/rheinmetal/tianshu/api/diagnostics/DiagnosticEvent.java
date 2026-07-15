package com.rheinmetal.tianshu.api.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record DiagnosticEvent(
        String moduleId,
        String code,
        DiagnosticSeverity severity,
        DiagnosticPrivacy privacy,
        long occurredAtMillis,
        Map<String, String> attributes
) {
    public DiagnosticEvent {
        moduleId = requireText(moduleId, "moduleId");
        code = requireText(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        privacy = Objects.requireNonNull(privacy, "privacy");
        attributes = immutableAttributes(attributes);
    }

    public static DiagnosticEvent now(
            String moduleId,
            String code,
            DiagnosticSeverity severity,
            DiagnosticPrivacy privacy,
            Map<String, String> attributes
    ) {
        return new DiagnosticEvent(moduleId, code, severity, privacy, System.currentTimeMillis(), attributes);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Map<String, String> immutableAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("diagnostic attribute key must not be blank");
            }
            copy.put(key, value == null ? "" : value);
        });
        return Map.copyOf(copy);
    }
}
