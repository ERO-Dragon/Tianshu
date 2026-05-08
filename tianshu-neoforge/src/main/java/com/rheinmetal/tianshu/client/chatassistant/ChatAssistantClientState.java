package com.rheinmetal.tianshu.client.chatassistant;

public final class ChatAssistantClientState {
    private boolean open;
    private String text = "";
    private long deadlineAtMillis;
    private long openedAtMillis;
    private String reason = "";
    private String hint = "";
    private long hintExpireAtMillis;

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized String text() {
        return text;
    }

    public synchronized long deadlineAtMillis() {
        return deadlineAtMillis;
    }

    public synchronized long openedAtMillis() {
        return openedAtMillis;
    }

    public synchronized String reason() {
        return reason;
    }

    public synchronized String hint() {
        if (System.currentTimeMillis() > hintExpireAtMillis) {
            return "";
        }
        return hint;
    }

    public synchronized void open(long deadlineAtMillis, String reason) {
        this.open = true;
        this.text = "";
        this.deadlineAtMillis = Math.max(0L, deadlineAtMillis);
        this.openedAtMillis = System.currentTimeMillis();
        this.reason = normalize(reason);
        this.hint = "";
        this.hintExpireAtMillis = 0L;
    }

    public synchronized void updateText(String text, long deadlineAtMillis, String reason) {
        this.open = true;
        this.text = normalize(text);
        this.deadlineAtMillis = Math.max(0L, deadlineAtMillis);
        this.reason = normalize(reason);
        if (this.openedAtMillis <= 0L) {
            this.openedAtMillis = System.currentTimeMillis();
        }
    }

    public synchronized void resetCountdown(String text, long deadlineAtMillis, String reason) {
        this.open = true;
        this.text = normalize(text);
        this.deadlineAtMillis = Math.max(0L, deadlineAtMillis);
        this.openedAtMillis = System.currentTimeMillis();
        this.reason = normalize(reason);
    }

    public synchronized void close() {
        this.open = false;
        this.text = "";
        this.deadlineAtMillis = 0L;
        this.openedAtMillis = 0L;
        this.reason = "";
    }

    public synchronized void showHint(String hint) {
        this.hint = normalize(hint);
        this.hintExpireAtMillis = System.currentTimeMillis() + 2_000L;
    }

    public synchronized void tick() {
        if (open && deadlineAtMillis > 0L && System.currentTimeMillis() >= deadlineAtMillis) {
            close();
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(open, text, deadlineAtMillis, openedAtMillis, reason, hint());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    public record Snapshot(boolean open, String text, long deadlineAtMillis, long openedAtMillis, String reason, String hint) {
    }
}
