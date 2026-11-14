package com.rheinmetal.tianshu.api.agent;

/**
 * A server-side entity that can act as the agent's body. The platform only knows its identity, permission and
 * position; inventory and game actions stay behind the game-side ports.
 */
public interface AgentBody {

    String id();

    boolean operator();

    ActorPosition position();
}
