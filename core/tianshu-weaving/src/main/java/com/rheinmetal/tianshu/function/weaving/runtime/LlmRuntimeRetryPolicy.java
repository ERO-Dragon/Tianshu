package com.rheinmetal.tianshu.function.weaving.runtime;

/**
 * How long the cloud lane waits before starting again after a failed start.
 *
 * <p>A failed start is almost always a missing or wrong setting, and a player who fixes it in the settings
 * page saves while the lane is down, so the retry has to be automatic; but a retry every second against a
 * dead endpoint is pointless traffic. The schedule therefore backs off: 5s, 10s, 20s, 40s, 60s, five
 * attempts in total, which is about two minutes of covering a transient network or provider outage before
 * the player is told instead of retried forever.
 *
 * <p>The numbers are named constants rather than configuration on purpose: they bound how long the lane may
 * stay silently unavailable, and a player-set interval could contradict that promise.
 */
public final class LlmRuntimeRetryPolicy {

    /** Attempts after the first failure, before the failure is reported instead of retried. */
    public static final int MAX_ATTEMPTS = 5;

    /** Wait before the first retry. */
    public static final long BASE_DELAY_MILLIS = 5_000L;

    /** Ceiling for the backoff, so the last attempts stay minutes rather than hours away. */
    public static final long MAX_DELAY_MILLIS = 60_000L;

    private LlmRuntimeRetryPolicy() {
    }

    /**
     * Wait before the given attempt.
     *
     * @param attempt 1-based retry attempt; a value below 1 is treated as the first attempt
     * @return the delay in milliseconds, never above {@link #MAX_DELAY_MILLIS}
     */
    public static long delayMillisForAttempt(int attempt) {
        long delay = BASE_DELAY_MILLIS;
        for (int current = 1; current < Math.max(1, attempt); current++) {
            delay = Math.min(MAX_DELAY_MILLIS, delay * 2L);
        }
        return delay;
    }

    /**
     * Whether another attempt may be scheduled.
     *
     * @param completedAttempts attempts already made
     * @return true while fewer than {@link #MAX_ATTEMPTS} attempts were made
     */
    public static boolean canRetry(int completedAttempts) {
        return completedAttempts < MAX_ATTEMPTS;
    }
}
