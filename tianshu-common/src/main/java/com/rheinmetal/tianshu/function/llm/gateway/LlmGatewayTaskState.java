package com.rheinmetal.tianshu.function.llm.gateway;

public enum LlmGatewayTaskState {
    CREATED,
    AUTHORIZING,
    ACCEPTED,
    QUEUED,
    SUBMITTED,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    REJECTED
}
