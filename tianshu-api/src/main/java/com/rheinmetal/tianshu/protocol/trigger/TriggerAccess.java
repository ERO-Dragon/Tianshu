package com.rheinmetal.tianshu.protocol.trigger;

import java.util.List;

public interface TriggerAccess {
    TriggerRegistrationResult register(TriggerRegistration registration);

    void unregisterModule(String moduleId);

    List<TriggerRegistration> registrations();
}
