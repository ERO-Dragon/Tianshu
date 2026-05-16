package com.rheinmetal.tianshu.protocol.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IntegrationModuleRegistry {
    private final List<IntegrationModuleDeclaration> declarations = new ArrayList<>();

    public synchronized void register(IntegrationModuleDeclaration declaration) {
        if (declaration == null) {
            throw new IllegalArgumentException("declaration cannot be null");
        }
        declarations.removeIf(existing -> existing.moduleId().equals(declaration.moduleId()));
        declarations.add(declaration);
    }

    public synchronized void unregister(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        String normalized = moduleId.trim();
        declarations.removeIf(existing -> existing.moduleId().equals(normalized));
    }

    public synchronized Optional<IntegrationModuleDeclaration> find(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return Optional.empty();
        }
        String normalized = moduleId.trim();
        return declarations.stream()
                .filter(declaration -> declaration.moduleId().equals(normalized))
                .findFirst();
    }

    public synchronized List<IntegrationModuleDeclaration> declarations() {
        return List.copyOf(declarations);
    }
}
