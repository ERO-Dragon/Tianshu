package com.rheinmetal.tianshu.protocol.broker;

import java.util.List;

public record BrokerSnapshot(String brokerId, int queueSize, int runningSize, List<String> runningEnvelopeIds) {
}
