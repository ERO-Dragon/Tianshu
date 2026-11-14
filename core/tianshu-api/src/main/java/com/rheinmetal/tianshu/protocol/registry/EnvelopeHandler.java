package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

@FunctionalInterface
public interface EnvelopeHandler {
    void handle(TianshuEnvelope envelope, ProtocolContext context) throws Exception;
}
