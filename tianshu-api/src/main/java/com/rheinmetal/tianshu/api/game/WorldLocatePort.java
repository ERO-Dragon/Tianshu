package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.tool.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * Finds the nearest instance of a structure or a biome, from an actor's position.
 *
 * <p>Atomic: one query, one answer. It does not walk there ({@code entity.move} does) and it does not decide whether going
 * is worth it — that is the caller's composition.</p>
 *
 * <p>Anchored on the actor rather than on coordinates because "nearest to me" is the question a body asks; a caller with
 * a different origin has that origin's actor.</p>
 */
public interface WorldLocatePort {

    long TIMEOUT_MILLIS = 35_000L;

    default CompletableFuture<LocateResult> locate(ActorRef actor, LocateRequest request) {
        return locate(actor, request, CancellationSignal.NEVER);
    }

    CompletableFuture<LocateResult> locate(ActorRef actor, LocateRequest request, CancellationSignal cancellation);
}