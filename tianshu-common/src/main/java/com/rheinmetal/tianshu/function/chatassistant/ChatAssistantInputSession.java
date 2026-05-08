package com.rheinmetal.tianshu.function.chatassistant;

public final class ChatAssistantInputSession {
    private final long sessionId;
    private final StringBuilder text = new StringBuilder();
    private final int maxTextLength;
    private long deadlineAtMillis;
    private long version;

    public ChatAssistantInputSession(long sessionId, long deadlineAtMillis, int maxTextLength) {
        this.sessionId = sessionId;
        this.deadlineAtMillis = Math.max(0L, deadlineAtMillis);
        this.maxTextLength = Math.max(1, maxTextLength);
        this.version = 1L;
    }

    public long sessionId() {
        return sessionId;
    }

    public String text() {
        return text.toString();
    }

    public long deadlineAtMillis() {
        return deadlineAtMillis;
    }

    public long version() {
        return version;
    }

    public boolean isExpired(long nowMillis) {
        return deadlineAtMillis > 0L && nowMillis >= deadlineAtMillis;
    }

    public boolean hasText() {
        return !text.toString().trim().isEmpty();
    }

    public void resetDeadline(long deadlineAtMillis) {
        this.deadlineAtMillis = Math.max(0L, deadlineAtMillis);
        version++;
    }

    public void clear() {
        text.setLength(0);
        version++;
    }

    public AppendResult appendText(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return AppendResult.EMPTY;
        }
        int separatorLength = text.isEmpty() ? 0 : 1;
        int allowed = maxTextLength - text.length() - separatorLength;
        if (allowed <= 0) {
            return AppendResult.FULL;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        if (normalized.length() > allowed) {
            text.append(normalized, 0, allowed);
            version++;
            return AppendResult.TRUNCATED;
        }
        text.append(normalized);
        version++;
        return AppendResult.APPENDED;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    public enum AppendResult {
        APPENDED,
        TRUNCATED,
        FULL,
        EMPTY
    }
}
