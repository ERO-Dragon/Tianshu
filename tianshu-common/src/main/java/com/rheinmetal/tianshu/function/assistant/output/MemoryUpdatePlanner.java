package com.rheinmetal.tianshu.function.assistant.output;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MemoryUpdatePlanner {
    private static final int MAX_CANDIDATE_TEXT_LENGTH = 240;

    public List<MemoryUpdateCandidate> plan(String requestKey, String userText, String assistantText) {
        String user = normalize(userText);
        String assistant = normalize(assistantText);
        if (user.isBlank() && assistant.isBlank()) {
            return List.of();
        }
        List<MemoryUpdateCandidate> candidates = new ArrayList<>();
        if (shouldConsiderLongTermMemory(user, assistant)) {
            candidates.add(new MemoryUpdateCandidate(
                    MemoryUpdateTarget.LONG_TERM_USER_MEMORY,
                    truncate(user),
                    "explicit_user_preference",
                    85,
                    requestKey,
                    System.currentTimeMillis()
            ));
        }
        if (shouldConsiderWorldSummary(user, assistant)) {
            candidates.add(new MemoryUpdateCandidate(
                    MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY,
                    truncate("用户：" + user + "\n助手：" + assistant),
                    "conversation_summary_candidate",
                    55,
                    requestKey,
                    System.currentTimeMillis()
            ));
        }
        return candidates.stream().filter(candidate -> !candidate.isEmpty()).toList();
    }

    public boolean shouldConsiderLongTermMemory(String userText, String assistantText) {
        String user = normalize(userText);
        if (user.isBlank() || looksRuntimeOnly(user)) {
            return false;
        }
        String lower = user.toLowerCase(Locale.ROOT);
        return user.contains("记住")
                || user.contains("以后")
                || user.contains("长期")
                || user.contains("偏好")
                || user.contains("我喜欢")
                || user.contains("我不喜欢")
                || lower.contains("remember")
                || lower.contains("my preference")
                || lower.contains("i prefer")
                || lower.contains("i like")
                || lower.contains("i dislike");
    }

    private boolean shouldConsiderWorldSummary(String userText, String assistantText) {
        String user = normalize(userText);
        String assistant = normalize(assistantText);
        if (user.isBlank() || assistant.isBlank()) {
            return false;
        }
        if (looksRuntimeOnly(user)) {
            return false;
        }
        return user.contains("目标")
                || user.contains("任务")
                || user.contains("计划")
                || user.contains("进度")
                || user.contains("下一步")
                || user.toLowerCase(Locale.ROOT).contains("goal")
                || user.toLowerCase(Locale.ROOT).contains("task")
                || user.toLowerCase(Locale.ROOT).contains("plan");
    }

    private boolean looksRuntimeOnly(String text) {
        String value = normalize(text);
        if (value.isBlank()) {
            return true;
        }
        return value.contains("我在哪")
                || value.contains("当前位置")
                || value.contains("背包")
                || value.contains("血量")
                || value.contains("饥饿")
                || value.contains("附近")
                || value.contains("坐标")
                || value.toLowerCase(Locale.ROOT).contains("where am i")
                || value.toLowerCase(Locale.ROOT).contains("inventory")
                || value.toLowerCase(Locale.ROOT).contains("nearby");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value) {
        String normalized = normalize(value);
        if (normalized.length() <= MAX_CANDIDATE_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CANDIDATE_TEXT_LENGTH);
    }
}
