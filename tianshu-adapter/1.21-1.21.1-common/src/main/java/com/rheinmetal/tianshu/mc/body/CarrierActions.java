package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentEntityUsePort;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.AgentItemUsePort;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.mc.tool.AgentItemSource;
import com.rheinmetal.tianshu.mc.tool.AgentItemUseSource;
import com.rheinmetal.tianshu.mc.tool.BlockInteractSource;
import com.rheinmetal.tianshu.mc.tool.BlockMachineSource;
import com.rheinmetal.tianshu.mc.tool.BlockPlaceSource;
import com.rheinmetal.tianshu.mc.tool.BodyQuerySource;
import com.rheinmetal.tianshu.mc.tool.ContainerTransferSource;
import com.rheinmetal.tianshu.mc.tool.CraftSource;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * The handlers the built-in carriers offer. This is the same contract a third-party mod implements, which is what
 * keeps the built-in carriers from getting a private path through the platform.
 *
 * <p>Each handler is created once per server registry and reused; handlers themselves are stateless beyond the
 * registry they read. A third-party carrier may return only the handlers it can perform.</p>
 */
public final class CarrierActions implements AgentBodyActions {

    private final AgentMotionPort motion;
    private final AgentActionPort action;
    private final AgentItemPort items;
    private final AgentWorldQueryPort query;
    private final BlockPlacePort placement;
    private final BlockInteractPort interaction;
    private final CraftPort craft;
    private final ContainerTransferPort transfer;
    private final BlockMachinePort machine;
    private final AgentItemUsePort itemUse;
    private final AgentEntityUsePort entityUse;

    public CarrierActions(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.motion = new AgentMotionSource(server, bodies);
        this.action = new AgentActionSource(server, bodies, actionPlayers);
        this.items = new AgentItemSource(server, bodies);
        this.query = new BodyQuerySource(server);
        this.placement = new BlockPlaceSource(server, bodies, actionPlayers);
        this.interaction = new BlockInteractSource(server, bodies, actionPlayers);
        this.craft = new CraftSource(server);
        this.transfer = new ContainerTransferSource(server);
        this.machine = new BlockMachineSource(server);
        this.itemUse = new AgentItemUseSource(server, bodies, actionPlayers);
        this.entityUse = new AgentEntityUseSource(server, bodies, actionPlayers);
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

    @Override
    public Optional<BlockInteractPort> interaction() {
        return Optional.of(interaction);
    }

    @Override
    public Optional<CraftPort> craft() {
        return Optional.of(craft);
    }

    @Override
    public Optional<ContainerTransferPort> transfer() {
        return Optional.of(transfer);
    }

    @Override
    public Optional<BlockMachinePort> machine() {
        return Optional.of(machine);
    }

    @Override
    public Optional<AgentItemUsePort> itemUse() {
        return Optional.of(itemUse);
    }

    @Override
    public Optional<AgentEntityUsePort> entityUse() {
        return Optional.of(entityUse);
    }
}