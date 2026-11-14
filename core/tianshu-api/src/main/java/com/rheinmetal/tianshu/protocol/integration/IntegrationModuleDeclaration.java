package com.rheinmetal.tianshu.protocol.integration;

import java.util.EnumSet;
import java.util.Set;

public record IntegrationModuleDeclaration(
        String moduleId,
        String moduleName,
        String moduleVersion,
        Set<IntegrationCapability> capabilities
) {
    public IntegrationModuleDeclaration {
        moduleId = requireText(moduleId, "moduleId");
        moduleName = moduleName == null || moduleName.isBlank() ? moduleId : moduleName.trim();
        moduleVersion = moduleVersion == null || moduleVersion.isBlank() ? "unknown" : moduleVersion.trim();
        capabilities = capabilities == null || capabilities.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(capabilities));
    }

    public boolean supports(IntegrationCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
