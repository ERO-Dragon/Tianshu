package com.rheinmetal.tianshu.function.weaving.service;

import java.time.Duration;

/**
 * Arms the deadline of one request.
 *
 * <p>The transport needs it because a provider can accept a stream and then stop sending: the JDK's
 * {@code HttpRequest.timeout} covers waiting for the response head, not for the bytes inside the body.
 * Without this, a stalled body blocks a reader thread and its connection until the OS gives up, and
 * nothing in the protocol layer would ever free them.</p>
 *
 * <p>The implementation owns the threading; the transport only asks for one callback and cancels it
 * when the request ends, so arming a deadline costs no thread of its own.</p>
 */
public interface LlmTimeoutScheduler {

    /** Runs {@code task} once after {@code delay}; the returned handle must be safe to cancel twice. */
    Handle schedule(Duration delay, Runnable task);

    interface Handle {
        void cancel();
    }
}