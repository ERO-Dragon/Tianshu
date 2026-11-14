package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;

import java.util.Objects;

public record ProtocolCapabilityRegistration(String moduleId, CapabilityDescriptor descriptor) {
    public ProtocolCapabilityRegistration {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId cannot be blank");
        }
        moduleId = moduleId.trim();
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }
}
