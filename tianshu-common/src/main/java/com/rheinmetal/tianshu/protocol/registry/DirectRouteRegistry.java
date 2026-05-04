package com.rheinmetal.tianshu.protocol.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DirectRouteRegistry {
    private final Map<String, HandlerRegistration> directRoutes = new ConcurrentHashMap<>();

    public void register(String routeId, ModuleDescriptor moduleDescriptor, CapabilityDescriptor descriptor, EnvelopeHandler handler) {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("routeId cannot be blank");
        }
        HandlerRegistration registration = new HandlerRegistration(moduleDescriptor, descriptor, handler);
        HandlerRegistration existing = directRoutes.putIfAbsent(routeId, registration);
        if (existing != null) {
            throw new IllegalStateException("Direct route already registered: " + routeId);
        }
    }

    public List<HandlerRegistration> findDirect(String routeId) {
        HandlerRegistration registration = directRoutes.get(routeId);
        return registration == null ? List.of() : List.of(registration);
    }

    public List<String> routeIds() {
        return new ArrayList<>(directRoutes.keySet());
    }
}
