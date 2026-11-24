package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Who a world-facing tool call runs as: the caller itself, or the body it names.
 *
 * <p>This is the single place that turns a {@link ToolCaller} plus an optional body id into an
 * {@link ActorRef}, so tools do not each re-implement the same three branches. Capability refusal stays in
 * {@link EntityActors#checkCapability} and is exposed here only so a tool can check before touching a port.</p>
 *
 * <p>An AI module can drive a body (that is a product decision, recorded as DEC-2): its call is judged with the same
 * tool permissions a player call would get — {@code ToolPermission} does not look at the caller kind — and the body it
 * names in {@code args.body} is adopted. It has to name one: falling through to "the caller itself" would invent an
 * actor nobody asked for, which is how these tools used to answer a refusal no agent could act on.</p>
 */
final class ActorScope {

    private final ActorRef actor;
    private final ToolException refusal;

    private ActorScope(ActorRef actor, ToolException refusal) {
        this.actor = actor;
        this.refusal = refusal;
    }

    static ActorScope of(ToolCaller caller, String bodyId) {
        boolean module = caller.kind() == ToolCaller.Kind.AGENT || caller.kind() == ToolCaller.Kind.MOD;
        if (caller.kind() == ToolCaller.Kind.BODY) {
            // A body always runs as itself. Naming a different body used to be ignored silently, which meant a call
            // could look accepted while acting as someone else.
            if (bodyId != null && !bodyId.isBlank() && !bodyId.trim().equals(caller.id())) {
                return new ActorScope(null, BodyFailure.WRONG_TARGET.exception());
            }
            return new ActorScope(ActorRef.of(caller), null);
        }
        if (bodyId != null && !bodyId.isBlank()) {
            String trimmed = bodyId.trim();
            Optional<ToolException> denied = EntityActors.checkAccess(caller, trimmed);
            if (denied.isPresent()) {
                return new ActorScope(null, denied.get());
            }
            return new ActorScope(ActorRef.body(trimmed, caller.operator()), null);
        }
        if (module) {
            return new ActorScope(null, BodyFailure.BODY_REQUIRED.exception());
        }
        return new ActorScope(ActorRef.of(caller), null);
    }

    Optional<ToolException> refusal() {
        return Optional.ofNullable(refusal);
    }

    ActorRef actor() {
        if (actor == null) {
            throw new IllegalStateException("a refused scope has no actor");
        }
        return actor;
    }

    /** Empty when this actor may perform the capability; a body that does not declare it is refused here. */
    Optional<ToolException> capabilityRefusal(EntityCapability capability) {
        return EntityActors.checkCapability(actor(), capability);
    }

    /**
     * Runs a long body operation under the body's single-operation slot, binding the call's cancellation signal so
     * the server-side tick loop can observe a cancel or timeout.
     */
    static <T> CompletableFuture<T> guarded(ActorRef actor, ToolContext context,
                                            Supplier<CompletableFuture<T>> operation) {
        if (actor.kind() != ActorRef.Kind.BODY) {
            return operation.get();
        }
        return BodyOperations.guarded(actor.id(), context.cancellation(), operation);
    }
}