package com.rheinmetal.tianshu.protocol.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DirectRouteRegistry {
    private final Map<String, List<HandlerRegistration>> directRoutes = new ConcurrentHashMap<>();

    public void register(String routeId, ModuleDescriptor moduleDescriptor, CapabilityDescriptor descriptor, EnvelopeHandler handler) {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("routeId cannot be blank");
        }
        HandlerRegistration registration = new HandlerRegistration(moduleDescriptor, descriptor, handler);
        directRoutes.compute(routeId, (key, existing) -> {
            List<HandlerRegistration> result = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            boolean duplicatePayload = result.stream()
                    .anyMatch(candidate -> candidate.capabilityDescriptor().supportedPayloadType() == descriptor.supportedPayloadType());
            if (duplicatePayload) {
                throw new IllegalStateException("Direct route already registered for payload: " + routeId + " / " + descriptor.supportedPayloadType());
            }
            result.add(registration);
            return List.copyOf(result);
        });
    }

    public List<HandlerRegistration> findDirect(String routeId) {
        return directRoutes.getOrDefault(routeId, List.of());
    }

    public void unregisterModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        String normalizedModuleId = moduleId.trim();
        directRoutes.entrySet().removeIf(entry -> {
            List<HandlerRegistration> remaining = entry.getValue().stream()
                    .filter(registration -> !registration.moduleDescriptor().moduleId().equals(normalizedModuleId))
                    .toList();
            if (remaining.isEmpty()) {
                return true;
            }
            entry.setValue(List.copyOf(remaining));
            return false;
        });
    }

    public List<String> routeIds() {
        return new ArrayList<>(directRoutes.keySet());
    }
}
