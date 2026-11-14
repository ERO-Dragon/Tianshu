package com.rheinmetal.tianshu.protocol.registry;

import java.util.List;
import java.util.Map;

final class HandlerRegistrationRegistrySupport {
    private HandlerRegistrationRegistrySupport() {
    }

    static void unregisterModule(Map<String, List<HandlerRegistration>> registrationsByKey, String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        String normalizedModuleId = moduleId.trim();
        for (String key : List.copyOf(registrationsByKey.keySet())) {
            registrationsByKey.computeIfPresent(key, (ignored, registrations) -> {
                List<HandlerRegistration> remaining = registrations.stream()
                        .filter(registration -> !registration.moduleDescriptor().moduleId().equals(normalizedModuleId))
                        .toList();
                return remaining.isEmpty() ? null : List.copyOf(remaining);
            });
        }
    }
}
