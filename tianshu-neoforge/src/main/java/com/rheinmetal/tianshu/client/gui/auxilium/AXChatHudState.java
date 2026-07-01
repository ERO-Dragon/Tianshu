package com.rheinmetal.tianshu.client.gui.auxilium;

import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputContext;

import java.util.Objects;

public final class AXChatHudState implements AXChatOutputSink {
    private static final long HOLD_MILLIS = 9000L;
    private static final int MAX_TEXT_LENGTH = 1200;

    private final Object lock = new Object();
    private AXOutputContext context;
    private StringBuilder text = new StringBuilder();
    private long updatedAtMillis;
    private boolean active;
    private boolean failed;

    @Override
    public void begin(AXOutputContext context) {
        synchronized (lock) {
            this.context = context;
            this.text = new StringBuilder();
            this.updatedAtMillis = System.currentTimeMillis();
            this.active = true;
            this.failed = false;
        }
    }

    @Override
    public void append(AXOutputContext context, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (!sameTurn(context)) {
                begin(context);
            }
            text.append(value);
            trimToMaxLength();
            updatedAtMillis = System.currentTimeMillis();
            active = true;
        }
    }

    @Override
    public void complete(AXOutputContext context, String fullText) {
        synchronized (lock) {
            if (sameTurn(context) && fullText != null && !fullText.isBlank()) {
                text = new StringBuilder(fullText);
                trimToMaxLength();
            }
            updatedAtMillis = System.currentTimeMillis();
            active = false;
            failed = false;
        }
    }

    @Override
    public void fail(AXOutputContext context, String reason) {
        synchronized (lock) {
            if (!sameTurn(context)) {
                this.context = context;
                this.text = new StringBuilder();
            }
            updatedAtMillis = System.currentTimeMillis();
            active = false;
            failed = true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (text.isEmpty() || (!active && now - updatedAtMillis > HOLD_MILLIS)) {
                return Snapshot.EMPTY;
            }
            return new Snapshot(text.toString(), active, failed, updatedAtMillis);
        }
    }

    private boolean sameTurn(AXOutputContext value) {
        if (context == null || value == null) {
            return false;
        }
        return Objects.equals(context.sessionId(), value.sessionId())
                && Objects.equals(context.turnId(), value.turnId())
                && Objects.equals(context.requestId(), value.requestId());
    }

    private void trimToMaxLength() {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return;
        }
        text.delete(0, text.length() - MAX_TEXT_LENGTH);
    }

    public record Snapshot(String text, boolean active, boolean failed, long updatedAtMillis) {
        private static final Snapshot EMPTY = new Snapshot("", false, false, 0L);

        public boolean empty() {
            return text == null || text.isBlank();
        }
    }
}
