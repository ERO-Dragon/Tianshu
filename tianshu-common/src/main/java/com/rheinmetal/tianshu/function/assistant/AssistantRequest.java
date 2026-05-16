package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.function.assistant.input.AssistantNormalizedInput;
import com.rheinmetal.tianshu.protocol.payload.LlmPromptPayload;

public record AssistantRequest(String requestKey, String userText, String providedContext) {
    public AssistantRequest {
        requestKey = requestKey == null || requestKey.isBlank() ? "assistant.request" : requestKey.trim();
        userText = userText == null ? "" : userText.trim();
        providedContext = providedContext == null ? "" : providedContext.trim();
    }

    public static AssistantRequest fromPayload(String requestKey, LlmPromptPayload payload) {
        if (payload == null) {
            return new AssistantRequest(requestKey, "", "");
        }
        return new AssistantRequest(requestKey, payload.text(), payload.context());
    }

    public static AssistantRequest fromNormalizedInput(AssistantNormalizedInput input) {
        if (input == null) {
            return new AssistantRequest("assistant.request", "", "");
        }
        return new AssistantRequest(input.requestKey(), input.userText(), input.providedContext());
    }
}
