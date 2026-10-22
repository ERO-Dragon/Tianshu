package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.function.Consumer;

public interface ProtocolContext {
    void submit(TianshuEnvelope envelope);
    void complete(String envelopeId);
    void fail(String envelopeId, String reasonCode, String message, Throwable throwable);
    void cancel(String envelopeId, String reasonCode, String message);
    boolean isCancelled(String envelopeId);
    void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback);
}
