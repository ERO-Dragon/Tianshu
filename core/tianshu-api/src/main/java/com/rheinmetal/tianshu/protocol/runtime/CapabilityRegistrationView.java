package com.rheinmetal.tianshu.protocol.runtime;

import java.util.List;

public interface CapabilityRegistrationView {
    List<ProtocolCapabilityRegistration> capabilityRegistrations(String capabilityId);

    default int capabilityProviderCount(String capabilityId) {
        return capabilityRegistrations(capabilityId).size();
    }
}
