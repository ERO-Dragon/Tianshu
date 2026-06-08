package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.PayloadType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ResponseHandlerRegistry {
    private final Map<String, List<HandlerRegistration>> responseHandlers = new ConcurrentHashMap<>();

    public void register(String requestEnvelopeId, ModuleDescriptor moduleDescriptor, CapabilityDescriptor descriptor, EnvelopeHandler handler) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            throw new IllegalArgumentException("requestEnvelopeId cannot be blank");
        }
        HandlerRegistration registration = new HandlerRegistration(moduleDescriptor, descriptor, handler);
        responseHandlers.compute(requestEnvelopeId.trim(), (key, existing) -> {
            List<HandlerRegistration> result = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            boolean duplicatePayload = result.stream()
                    .anyMatch(candidate -> candidate.moduleDescriptor().moduleId().equals(moduleDescriptor.moduleId())
                            && candidate.capabilityDescriptor().supportedPayloadType() == descriptor.supportedPayloadType());
            if (duplicatePayload) {
                throw new IllegalStateException("Response handler already registered for request payload: "
                        + requestEnvelopeId + " / " + descriptor.supportedPayloadType());
            }
            result.add(registration);
            return List.copyOf(result);
        });
    }

    public List<HandlerRegistration> findResponse(String requestEnvelopeId, PayloadType payloadType) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return List.of();
        }
        List<HandlerRegistration> registrations = responseHandlers.getOrDefault(requestEnvelopeId.trim(), List.of());
        if (registrations.isEmpty()) {
            return List.of();
        }
        return registrations.stream()
                .filter(registration -> registration.capabilityDescriptor().supportedPayloadType() == payloadType)
                .toList();
    }

    public void unregisterRequest(String requestEnvelopeId) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return;
        }
        responseHandlers.remove(requestEnvelopeId.trim());
    }

    public void unregisterModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        String normalizedModuleId = moduleId.trim();
        responseHandlers.entrySet().removeIf(entry -> {
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

    public List<String> requestEnvelopeIds() {
        return new ArrayList<>(responseHandlers.keySet());
    }
}
