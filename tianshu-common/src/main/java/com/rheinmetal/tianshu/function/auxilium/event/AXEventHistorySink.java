package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * The one place an event entry leaves the decision layer for the history.
 *
 * <p>This is an adapter onto the existing recent-dialogue channel, not a second channel: the owner
 * turns an {@link AXEventAppend} into the same {@code world_event} raw turn it already stores for
 * third-party utterances.
 */
@FunctionalInterface
public interface AXEventHistorySink {
    void append(AXEventAppend append);
}
