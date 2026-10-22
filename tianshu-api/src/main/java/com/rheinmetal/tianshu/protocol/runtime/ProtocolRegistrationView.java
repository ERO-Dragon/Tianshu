package com.rheinmetal.tianshu.protocol.runtime;

public interface ProtocolRegistrationView extends CapabilityRegistrationView {
    int topicSubscriberCount(String topicId);
}
