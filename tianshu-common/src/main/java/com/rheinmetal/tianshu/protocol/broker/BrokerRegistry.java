package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.runtime.MainThreadExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BrokerRegistry {
    private final Map<String, ProtocolBroker> brokers = new ConcurrentHashMap<>();
    private final MainThreadExecutor mainThreadExecutor;

    public BrokerRegistry(MainThreadExecutor mainThreadExecutor) {
        this.mainThreadExecutor = mainThreadExecutor;
    }

    public ProtocolBroker brokerFor(String target, BrokerType type, int queueCapacity, int maxConcurrency) {
        String key = type.name() + ":" + target;
        return brokers.computeIfAbsent(key, ignored -> createBroker(key, type, queueCapacity, maxConcurrency));
    }

    public List<BrokerSnapshot> snapshots() {
        List<BrokerSnapshot> result = new ArrayList<>();
        for (ProtocolBroker broker : brokers.values()) {
            result.add(broker.snapshot());
        }
        return result;
    }

    public void cancel(String envelopeId, String reasonCode, String message) {
        for (ProtocolBroker broker : brokers.values()) {
            broker.cancel(envelopeId, reasonCode, message);
        }
    }

    private ProtocolBroker createBroker(String brokerId, BrokerType type, int queueCapacity, int maxConcurrency) {
        return switch (type) {
            case EXCLUSIVE_INTERRUPT -> new ExclusiveInterruptBroker(brokerId, queueCapacity);
            case PARALLEL_LIMIT -> new ParallelLimitBroker(brokerId, queueCapacity, maxConcurrency);
            case LATEST_ONLY -> new LatestOnlyBroker(brokerId);
            case BOUNDED_QUEUE -> new BoundedQueueBroker(brokerId, queueCapacity, maxConcurrency);
            case STATELESS_FAST_PATH -> new StatelessFastPathBroker(brokerId);
            case MAIN_THREAD -> new MainThreadBroker(brokerId, mainThreadExecutor);
            case SERVER_PACKET -> new ServerPacketBroker(brokerId, queueCapacity);
        };
    }
}
