package com.rheinmetal.tianshu.function.ia.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * A user gesture that asks the arbiter to broadcast a stop request for the dialogue's current target.
 *
 * <p>It carries no text and no target. The target is decided when the request arrives, because only the
 * arbiter holds the interaction context and the live lease; a gesture is not a turn, so it must not bring its
 * own claim.</p>
 */
public record DialogueInterruptRequestPayload(long occurredAtMillis) implements ITianshuPayload {
    public DialogueInterruptRequestPayload {
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }
}