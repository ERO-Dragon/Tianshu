package com.rheinmetal.tianshu.function.llm.gateway;

public record LlmGatewayAdmissionResult(boolean accepted, LlmGatewayRequest request, LlmGatewayError error) {
    public static LlmGatewayAdmissionResult accepted(LlmGatewayRequest request) {
        return new LlmGatewayAdmissionResult(true, request, null);
    }

    public static LlmGatewayAdmissionResult rejected(String code, String message) {
        return new LlmGatewayAdmissionResult(false, null, new LlmGatewayError(code, message));
    }
}
