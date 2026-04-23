package com.rheinmetal.tianshu.event;

import java.util.concurrent.atomic.AtomicLong;

public abstract class TianshuEvent {
    private static final AtomicLong EVENT_SEQ = new AtomicLong(1);

    private final long sessionId;
    private final long createdAt;
    private final long eventId;

    protected TianshuEvent() {
        this(0L);
    }

    protected TianshuEvent(long sessionId) {
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.eventId = EVENT_SEQ.getAndIncrement();
    }

    public long getSessionId() {
        return sessionId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getEventId() {
        return eventId;
    }
}
