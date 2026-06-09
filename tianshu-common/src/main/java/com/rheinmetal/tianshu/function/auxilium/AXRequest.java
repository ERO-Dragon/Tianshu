package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.input.AXInputSource;
import com.rheinmetal.tianshu.function.auxilium.input.AXNormalizedInput;

public record AXRequest(String requestKey, String userText, String providedContext, AXInputSource source) {
    public AXRequest(String requestKey, String userText, String providedContext) {
        this(requestKey, userText, providedContext, AXInputSource.UNKNOWN);
    }

    public AXRequest {
        requestKey = requestKey == null || requestKey.isBlank() ? "AX.request" : requestKey.trim();
        userText = userText == null ? "" : userText.trim();
        providedContext = providedContext == null ? "" : providedContext.trim();
        source = source == null ? AXInputSource.UNKNOWN : source;
    }

    public static AXRequest fromNormalizedInput(AXNormalizedInput input) {
        if (input == null) {
            return new AXRequest("AX.request", "", "");
        }
        return new AXRequest(input.requestKey(), input.userText(), input.providedContext(), input.source());
    }
}
