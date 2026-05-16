package com.rheinmetal.tianshu.function.llm.gateway;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public interface LlmUsageAuthorizer {
    LlmUsageAuthorizationStartResult startAuthorization(LlmGatewayRequest request, TianshuEnvelope parent);

    void handleAuthorizationResult(TianshuEnvelope envelope, java.util.function.Consumer<LlmUsageAuthorizationCompletion> completionConsumer);

    void cancel(String taskId);
}
