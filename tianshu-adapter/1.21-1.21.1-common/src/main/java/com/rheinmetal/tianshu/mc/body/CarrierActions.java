package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.mc.tool.AgentItemSource;
import com.rheinmetal.tianshu.mc.tool.BlockPlaceSource;
import com.rheinmetal.tianshu.mc.tool.BodyQuerySource;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * The handlers the built-in carriers offer. This is the same contract a third-party mod implements, which is what
 * keeps the built-in carriers from getting a private path through the platform.
 *
 * <p>Each handler is created once per server registry and reused; handlers themselves are stateless beyond the
 * registry they read.</p>
 */
public final class CarrierActions implements AgentBodyActions {

    private final AgentMotionPort motion;
    private final AgentActionPort action;
    private final AgentItemPort items;
    private final AgentWorldQueryPort query;
    private final BlockPlacePort placement;

    public CarrierActions(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.motion = new AgentMotionSource(server, bodies);
        this.action = new AgentActionSource(server, bodies);
        this.items = new AgentItemSource(server, bodies);
        this.query = new BodyQuerySource(server);
        this.placement = new BlockPlaceSource(server, bodies, actionPlayers);
    }

    @Override
    public Optional<AgentMotionPort> motion() {
        return Optional.of(motion);
    }

    @Override
    public Optional<AgentActionPort> action() {
        return Optional.of(action);
    }

    @Override
    public Optional<AgentItemPort> items() {
        return Optional.of(items);
    }

    @Override
    public Optional<AgentWorldQueryPort> query() {
        return Optional.of(query);
    }

    @Override
    public Optional<BlockPlacePort> placement() {
        return Optional.of(placement);
    }
}