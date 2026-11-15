package com.rheinmetal.tianshu.api.agent;

import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.List;
import java.util.Optional;

/**
 * Owns one or more agent bodies: it resolves ids, enumerates live bodies and offers the handlers that act on them.
 *
 * <p>A provider that cannot act returns no {@link AgentBodyActions}; one that can returns the subset it supports.
 * The built-in carriers and a third-party mod's carrier use exactly this contract.</p>
 */
public interface AgentBodyProvider {

    Optional<AgentBody> resolve(String bodyId);

    /** Every live body this provider holds; a provider that cannot enumerate returns none. */
    default List<AgentBody> all() {
        return List.of();
    }

    /** The handlers for one of this provider's bodies; empty when the provider offers no actions. */
    default Optional<AgentBodyActions> actions(String bodyId) {
        return Optional.empty();
    }

    /**
     * Empty when {@code caller} may command this body; otherwise the refusal to surface.
     *
     * <p>Only player-driven calls are judged here; the caller runtime and the server already carry operator
     * permission, and a provider without a policy allows everyone (the platform's historical behaviour).</p>
     */
    default Optional<ToolException> checkAccess(ToolCaller caller, String bodyId) {
        return Optional.empty();
    }
}