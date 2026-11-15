package com.rheinmetal.tianshu.api.agent;

import java.util.Set;

/**
 * A server-side entity that can act as the agent's body. The platform only knows its identity, permission,
 * position and what it can do; inventory and game actions stay behind the game-side ports.
 *
 * <p>A body is not assumed to be humanoid or alive in the player sense: it may float, have no health and no
 * hands. Capabilities say what it can do, and a missing capability is a refusal rather than a failure.</p>
 */
public interface AgentBody {

    String id();

    boolean operator();

    ActorPosition position();

    Set<BodyCapability> capabilities();
}