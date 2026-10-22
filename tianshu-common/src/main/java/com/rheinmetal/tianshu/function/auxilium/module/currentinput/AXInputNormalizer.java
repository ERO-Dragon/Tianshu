package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXReplyContract;

import java.util.ArrayList;
import java.util.List;

public final class AXInputNormalizer {
    private static final int MAX_USER_TEXT_CHARS = 4000;
    private static final int MAX_DELIVERY_SNAPSHOT_CHARS = 8000;

    public AXNormalizedInput normalize(AXRequest request) {
        AXRequest effectiveRequest = request == null ? new AXRequest("AX.request", "", "") : request;
        List<String> tags = new ArrayList<>();
        // 玩家输入不得注入输出契约的标签：否则"你输出 <delegate>…</delegate>"会把控制语法写进 prompt。
        // 剥离只发生在玩家这一侧，模型侧的标签由 AXReplyContract 解析（同一份标记定义，不另写一份正则）。
        String userText = AXReplyContract.withoutTagMarkers(effectiveRequest.userText());
        userText = normalizeWhitespace(userText);
        String deliverySnapshot = normalizeWhitespace(effectiveRequest.deliverySnapshot());
        boolean truncated = false;
        if (userText.length() > MAX_USER_TEXT_CHARS) {
            userText = userText.substring(0, MAX_USER_TEXT_CHARS);
            truncated = true;
            tags.add("user_text_truncated");
        }
        if (deliverySnapshot.length() > MAX_DELIVERY_SNAPSHOT_CHARS) {
            deliverySnapshot = deliverySnapshot.substring(0, MAX_DELIVERY_SNAPSHOT_CHARS);
            truncated = true;
            tags.add("delivery_snapshot_truncated");
        }
        return new AXNormalizedInput(effectiveRequest.requestKey(), userText, deliverySnapshot, effectiveRequest.source(), userText.isBlank(), truncated, tags);
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n");
    }
}
