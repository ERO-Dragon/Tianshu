package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.util.Optional;

/**
 * Who a world-facing tool call runs as: the caller itself, or the body it names.
 *
 * <p>This is the single place that turns a {@link ToolCaller} plus an optional body id into an
 * {@link ActorRef}, so tools do not each re-implement the same three branches. Capability refusal stays in
 * {@link AgentBodies#checkCapability} and is exposed here only so a tool can check before touching a port.</p>
 */
final class ActorScope {

    private final ActorRef actor;
    private final ToolException refusal;

    private ActorScope(ActorRef actor, ToolException refusal) {
        this.actor = actor;
        this.refusal = refusal;
    }

    static ActorScope of(ToolCaller caller, String bodyId, ToolId tool) {
        if (caller.kind() != ToolCaller.Kind.PLAYER && caller.kind() != ToolCaller.Kind.BODY) {
            return new ActorScope(null, new ToolException(ToolError.NO_PERMISSION,
                    tool.name() + " runs as a player or an agent body"));
        }
        if (caller.kind() == ToolCaller.Kind.BODY) {
            return new ActorScope(ActorRef.of(caller), null);
        }
        if (bodyId != null && !bodyId.isBlank()) {
            String trimmed = bodyId.trim();
            Optional<ToolException> denied = AgentBodies.checkAccess(caller, trimmed);
            if (denied.isPresent()) {
                return new ActorScope(null, denied.get());
            }
            return new ActorScope(ActorRef.body(trimmed, caller.operator()), null);
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
    Optional<ToolException> capabilityRefusal(BodyCapability capability) {
        return AgentBodies.checkCapability(actor(), capability);
    }
}