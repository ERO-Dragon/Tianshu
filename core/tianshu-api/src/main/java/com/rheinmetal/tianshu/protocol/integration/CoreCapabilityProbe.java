package com.rheinmetal.tianshu.protocol.integration;

import java.util.Set;

public record CoreCapabilityProbe(
        boolean corePresent,
        String coreVersion,
        String apiVersion,
        Set<IntegrationCapability> supportedCapabilities
) {
    public CoreCapabilityProbe {
        if (coreVersion == null || coreVersion.isBlank()) coreVersion = "unknown";
        if (apiVersion == null || apiVersion.isBlank()) apiVersion = "1";
        supportedCapabilities = supportedCapabilities == null || supportedCapabilities.isEmpty()
                ? Set.of()
                : Set.copyOf(supportedCapabilities);
    }

    public static CoreCapabilityProbe current() {
        return new CoreCapabilityProbe(
                true,
                // Stamped by the adapter's build; the literals that used to sit here described nothing.
                com.rheinmetal.tianshu.api.BuildInfo.version(),
                com.rheinmetal.tianshu.api.BuildInfo.apiVersion(),
                Set.of(
                        IntegrationCapability.VOICE_TRIGGER,
                        IntegrationCapability.DIALOGUE_PARTICIPANT,
                        IntegrationCapability.MODULE_STATUS_PROVIDER,
                        IntegrationCapability.MODULE_STATUS_CONSUMER,
                        IntegrationCapability.GUI_RENDER_CONTRIBUTOR,
                        IntegrationCapability.LIFECYCLE_LISTENER,
                        IntegrationCapability.RESOURCE_RELOAD_LISTENER
                )
        );
    }

    public boolean supports(IntegrationCapability capability) {
        return capability != null && supportedCapabilities.contains(capability);
    }
}

