package com.rheinmetal.tianshu.protocol.broker;

public final class ServerPacketBroker extends AbstractQueueBroker {
    private volatile boolean serverAuthorized;

    public ServerPacketBroker(String brokerId, int queueCapacity) {
        super(brokerId, queueCapacity, 1);
    }

    public void setServerAuthorized(boolean serverAuthorized) {
        this.serverAuthorized = serverAuthorized;
    }

    @Override
    public BrokerSubmitResult submit(com.rheinmetal.tianshu.protocol.TianshuEnvelope envelope, com.rheinmetal.tianshu.protocol.registry.HandlerRegistration registration, com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime runtime) {
        if (!serverAuthorized) {
            runtime.lifecycle().transition(envelope.envelopeId(), com.rheinmetal.tianshu.protocol.EnvelopeStatus.REJECTED, "PERMISSION_DENIED", "Server action is not authorized");
            return BrokerSubmitResult.rejected("PERMISSION_DENIED", "Server action is not authorized");
        }
        return super.submit(envelope, registration, runtime);
    }
}
