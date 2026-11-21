package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.dubhe.module.currentinput.DubheInputSource;
import com.rheinmetal.tianshu.dubhe.module.currentinput.DubheNormalizedInput;

public record DubheRequest(String requestKey, String userText, String deliverySnapshot, DubheInputSource source) {
    public DubheRequest(String requestKey, String userText, String deliverySnapshot) {
        this(requestKey, userText, deliverySnapshot, DubheInputSource.UNKNOWN);
    }

    public DubheRequest {
        requestKey = requestKey == null || requestKey.isBlank() ? "DUBHE.request" : requestKey.trim();
        userText = userText == null ? "" : userText.trim();
        deliverySnapshot = deliverySnapshot == null ? "" : deliverySnapshot.trim();
        source = source == null ? DubheInputSource.UNKNOWN : source;
    }

    public static DubheRequest fromNormalizedInput(DubheNormalizedInput input) {
        if (input == null) {
            return new DubheRequest("DUBHE.request", "", "");
        }
        return new DubheRequest(input.requestKey(), input.userText(), input.deliverySnapshot(), input.source());
    }
}
