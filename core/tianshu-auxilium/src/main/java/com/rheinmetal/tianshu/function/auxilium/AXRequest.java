package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputSource;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXNormalizedInput;

public record AXRequest(String requestKey, String userText, String deliverySnapshot, AXInputSource source) {
    public AXRequest(String requestKey, String userText, String deliverySnapshot) {
        this(requestKey, userText, deliverySnapshot, AXInputSource.UNKNOWN);
    }

    public AXRequest {
        requestKey = requestKey == null || requestKey.isBlank() ? "AX.request" : requestKey.trim();
        userText = userText == null ? "" : userText.trim();
        deliverySnapshot = deliverySnapshot == null ? "" : deliverySnapshot.trim();
        source = source == null ? AXInputSource.UNKNOWN : source;
    }

    public static AXRequest fromNormalizedInput(AXNormalizedInput input) {
        if (input == null) {
            return new AXRequest("AX.request", "", "");
        }
        return new AXRequest(input.requestKey(), input.userText(), input.deliverySnapshot(), input.source());
    }
}
