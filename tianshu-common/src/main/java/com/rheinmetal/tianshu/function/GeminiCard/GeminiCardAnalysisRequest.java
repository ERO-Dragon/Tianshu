package com.rheinmetal.tianshu.function.GeminiCard;

import java.util.Map;

public record GeminiCardAnalysisRequest(
        GeminiCardItemData equipped,
        GeminiCardItemData hovered,
        String semanticKey,
        String equippedMechanismSummary,
        String hoveredMechanismSummary,
        String prompt
) {
    public static GeminiCardAnalysisRequest difference(GeminiCardItemData equipped, GeminiCardItemData hovered) {
        String equippedSummary = summarizeMechanisms(equipped);
        String hoveredSummary = summarizeMechanisms(hovered);
        String semanticKey = equipped.semanticKey() + "->" + hovered.semanticKey();
        String prompt = "你是一个严谨的游戏数据比对器。已知：当前穿戴物品的特征为 [" + equippedSummary
                + "]，悬停物品的特征为 [" + hoveredSummary
                + "]。请仅基于上述已知数据，回答这件装备相比当前装备有哪些明确存在的机制差异。严禁猜测、严禁使用你的背景知识补完、不知道请直接回答未知。限制30字以内。";
        return new GeminiCardAnalysisRequest(equipped, hovered, semanticKey, equippedSummary, hoveredSummary, prompt);
    }

    private static String summarizeMechanisms(GeminiCardItemData item) {
        if (item == null || item.mechanisms() == null || item.mechanisms().isEmpty()) {
            return "未知";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (GeminiCardMechanismKey key : GeminiCardMechanismKey.PROMPT_ORDER) {
            String value = item.mechanisms().get(key.label());
            if (value == null || value.isBlank()) {
                continue;
            }
            if (count > 0) {
                builder.append("；");
            }
            builder.append(key.label()).append("=").append(value);
            count++;
            if (count >= 10) {
                break;
            }
        }
        if (count == 0) {
            return "未知";
        }
        return builder.toString();
    }
}
