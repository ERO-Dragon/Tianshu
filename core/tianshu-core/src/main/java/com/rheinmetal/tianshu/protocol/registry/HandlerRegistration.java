package com.rheinmetal.tianshu.protocol.registry;

import java.util.Objects;

public final class HandlerRegistration {
    private final ModuleDescriptor moduleDescriptor;
    private final CapabilityDescriptor capabilityDescriptor;
    private final EnvelopeHandler handler;

    public HandlerRegistration(ModuleDescriptor moduleDescriptor, CapabilityDescriptor capabilityDescriptor, EnvelopeHandler handler) {
        this.moduleDescriptor = Objects.requireNonNull(moduleDescriptor, "moduleDescriptor");
        this.capabilityDescriptor = Objects.requireNonNull(capabilityDescriptor, "capabilityDescriptor");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public ModuleDescriptor moduleDescriptor() { return moduleDescriptor; }
    public CapabilityDescriptor capabilityDescriptor() { return capabilityDescriptor; }
    public EnvelopeHandler handler() { return handler; }
}
