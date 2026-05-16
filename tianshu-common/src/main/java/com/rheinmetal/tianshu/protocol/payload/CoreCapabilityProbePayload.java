package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.integration.CoreCapabilityProbe;
import com.rheinmetal.tianshu.protocol.integration.IntegrationCapability;

import java.util.Set;

public record CoreCapabilityProbePayload(
        boolean corePresent,
        String coreVersion,
        String apiVersion,
        Set<IntegrationCapability> supportedCapabilities
) implements ITianshuPayload {
    public CoreCapabilityProbePayload {
        CoreCapabilityProbe probe = new CoreCapabilityProbe(corePresent, coreVersion, apiVersion, supportedCapabilities);
        corePresent = probe.corePresent();
        coreVersion = probe.coreVersion();
        apiVersion = probe.apiVersion();
        supportedCapabilities = probe.supportedCapabilities();
    }

    public static CoreCapabilityProbePayload current() {
        CoreCapabilityProbe probe = CoreCapabilityProbe.current();
        return new CoreCapabilityProbePayload(probe.corePresent(), probe.coreVersion(), probe.apiVersion(), probe.supportedCapabilities());
    }
}
