package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public interface ProtocolBroker {
    BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime);
    BrokerSnapshot snapshot();
    void cancel(String envelopeId, String reasonCode, String message);
    String brokerId();

    /**
     * Whether a full queue may refuse this envelope.
     *
     * <p>A full queue cannot hold everything, so something has to be dropped; the question is what. The answer here is
     * that the priorities carrying a storm's own control traffic — a cancel, a state sync — must survive it, because
     * dropping them is what turns a burst into a stuck session. Everything below {@code floor} is refused, and the
     * caller gets {@code BROKER_QUEUE_FULL} instead of an envelope that silently waits forever.</p>
     *
     * <p>This is a comparison against a named priority rather than a weight literal: the earlier {@code weight() < 400}
     * looked like an arbitrary threshold and was in fact exactly "HIGH and CRITICAL", which only the enum can say.</p>
     */
    static boolean belowFloor(Priority floor, TianshuEnvelope envelope) {
        return !envelope.header().priority().atLeast(floor);
    }
}