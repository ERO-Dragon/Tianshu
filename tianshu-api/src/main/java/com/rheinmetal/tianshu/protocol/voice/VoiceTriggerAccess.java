package com.rheinmetal.tianshu.protocol.voice;

import java.util.List;

public interface VoiceTriggerAccess {
    VoiceTriggerRegistrationResult register(VoiceTriggerRegistration registration);

    void unregisterModule(String moduleId);

    List<VoiceTriggerRegistration> registrations();
}
