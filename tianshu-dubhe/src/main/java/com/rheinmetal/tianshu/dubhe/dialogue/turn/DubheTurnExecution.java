package com.rheinmetal.tianshu.dubhe.dialogue.turn;

import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.dubhe.dialogue.input.DubheDialogueInput;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.rheinmetal.tianshu.presence.payload.PresenceActivityType;

final class DubheTurnExecution {
    private final long generation;
    private final TianshuEnvelope deliveryEnvelope;
    private final DubheDialogueInput delivery;
    private final DubheScope scope;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean releaseRequested = new AtomicBoolean(false);
    private final AtomicBoolean assistantPersisted = new AtomicBoolean(false);
    private final AtomicReference<PresenceActivityType> presenceActivity = new AtomicReference<>();
    private final StringBuilder displayedText = new StringBuilder();
    private volatile String dynamicFactRequestId = "";
    private volatile String requestKey = "";
    private volatile String llmRequestId = "";
    private volatile DubheTurnCancellation cancellation;
    private volatile DubheTurnLatencyTracker latencyTracker;

    DubheTurnExecution(long generation, TianshuEnvelope deliveryEnvelope, DubheDialogueInput delivery, DubheScope scope) {
        this.generation = generation;
        this.deliveryEnvelope = Objects.requireNonNull(deliveryEnvelope, "deliveryEnvelope");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.scope = scope == null ? DubheScope.unknown() : scope;
    }

    long generation() {
        return generation;
    }

    TianshuEnvelope deliveryEnvelope() {
        return deliveryEnvelope;
    }

    DubheDialogueInput delivery() {
        return delivery;
    }

    DubheScope scope() {
        return scope;
    }

    String activityId() {
        return "dubhe.chat." + delivery.sessionId() + "." + delivery.turnId();
    }

    PresenceActivityType swapPresenceActivity(PresenceActivityType next) {
        return presenceActivity.getAndSet(next);
    }

    /**
     * Marks this turn cancelled and records the reason.
     *
     * <p>Synchronized with {@link #cancellationIfCancelled()}: the flag is published before the
     * reason is written, so without a common monitor a thread could observe {@code cancelled == true}
     * while {@code cancellation} is still {@code null}. The run outcome requires every
     * {@code CANCELLED} terminal to carry its reason (design §12.3), which is exactly the case that
     * race used to produce.</p>
     */
    synchronized boolean cancel(DubheTurnCancellation reason) {
        boolean changed = cancelled.compareAndSet(false, true);
        if (changed) {
            cancellation = reason;
        }
        return changed;
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Reads {@code (cancelled, reason)} as one consistent pair — {@code null} when the turn is not
     * cancelled.
     *
     * <p>The run cancellation probe takes both its predicate and its reason from this single call
     * (design §12.3), so "cancelled with no reason" cannot be observed.</p>
     */
    synchronized DubheTurnCancellation cancellationIfCancelled() {
        return cancelled.get() ? cancellation : null;
    }

    void latencyTracker(DubheTurnLatencyTracker tracker) {
        latencyTracker = tracker;
    }

    DubheTurnLatencyTracker latencyTracker() {
        DubheTurnLatencyTracker tracker = latencyTracker;
        return tracker == null ? DubheTurnLatencyTracker.noop() : tracker;
    }

    void dynamicFactRequestId(String requestId) {
        dynamicFactRequestId = requestId == null ? "" : requestId;
    }

    String dynamicFactRequestId() {
        return dynamicFactRequestId;
    }

    void requestKey(String value) {
        requestKey = value == null ? "" : value;
    }

    String requestKey() {
        return requestKey;
    }

    void llmRequestId(String requestId) {
        llmRequestId = requestId == null ? "" : requestId;
    }

    String llmRequestId() {
        return llmRequestId;
    }

    boolean isCurrent(AtomicLong currentGeneration) {
        return !isCancelled() && currentGeneration != null && currentGeneration.get() == generation;
    }

    boolean requestRelease() {
        return releaseRequested.compareAndSet(false, true);
    }

    boolean markAssistantPersisted() {
        return assistantPersisted.compareAndSet(false, true);
    }

    synchronized void appendDisplayedText(String text) {
        if (text != null && !text.isBlank()) {
            displayedText.append(text);
        }
    }

    synchronized String displayedText() {
        return displayedText.toString();
    }
}
