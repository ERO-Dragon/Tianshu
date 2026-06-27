package com.rheinmetal.tianshu.client.presence;

public final class PresenceRefreshPolicy {
    public static final long INPUT_EVENT_MIN_INTERVAL_MILLIS = 75L;
    public static final long DETAILED_REFRESH_INTERVAL_MILLIS = 1_000L;
    public static final long DETAILED_SNAPSHOT_STALE_MILLIS = 8_000L;

    private PresenceRefreshPolicy() {
    }
}
