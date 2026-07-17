package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class AXTurnExecution {
    private final long generation;
    private final TianshuEnvelope deliveryEnvelope;
    private final DialogueDeliveryPayload delivery;
    private final AXScope scope;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean releaseRequested = new AtomicBoolean(false);
    private final AtomicBoolean assistantPersisted = new AtomicBoolean(false);
    private final StringBuilder displayedText = new StringBuilder();
    private volatile String dynamicFactRequestId = "";
    private volatile String requestKey = "";
    private volatile String llmRequestId = "";
    private volatile AXTurnCancellation cancellation;

    AXTurnExecution(long generation, TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery, AXScope scope) {
        this.generation = generation;
        this.deliveryEnvelope = Objects.requireNonNull(deliveryEnvelope, "deliveryEnvelope");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.scope = scope == null ? AXScope.unknown() : scope;
    }

    long generation() {
        return generation;
    }

    TianshuEnvelope deliveryEnvelope() {
        return deliveryEnvelope;
    }

    DialogueDeliveryPayload delivery() {
        return delivery;
    }

    AXScope scope() {
        return scope;
    }

    boolean cancel(AXTurnCancellation reason) {
        boolean changed = cancelled.compareAndSet(false, true);
        if (changed) {
            cancellation = reason;
        }
        return changed;
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    AXTurnCancellation cancellation() {
        return cancellation;
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
