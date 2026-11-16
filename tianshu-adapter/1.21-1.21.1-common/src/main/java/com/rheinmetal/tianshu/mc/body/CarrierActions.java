package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityUsePort;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
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
public final class CarrierActions implements EntityActions {

    private final EntityMotionPort motion;
    private final EntityActionPort action;
    private final EntityItemPort items;
    private final EntityQueryPort query;
    private final BlockPlacePort placement;
    private final BlockInteractPort interaction;
    private final CraftPort craft;
    private final ContainerTransferPort transfer;
    private final BlockMachinePort machine;
    private final EntityItemUsePort itemUse;
    private final EntityUsePort entityUse;

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
    public Optional<EntityMotionPort> motion() {
        return Optional.of(motion);
    }

    @Override
    public Optional<EntityActionPort> action() {
        return Optional.of(action);
    }

    @Override
    public Optional<EntityItemPort> items() {
        return Optional.of(items);
    }

    @Override
    public Optional<EntityQueryPort> query() {
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
    public Optional<EntityItemUsePort> itemUse() {
        return Optional.of(itemUse);
    }

    @Override
    public Optional<EntityUsePort> entityUse() {
        return Optional.of(entityUse);
    }
}