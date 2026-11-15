package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.agent.AgentItemUsePort;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.assembly.PlayerAuthority;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ServerToolDispatch;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCatalogPushRegistry;
import com.rheinmetal.tianshu.client.tool.BlockInteractTool;
import com.rheinmetal.tianshu.client.tool.BlockMachineTool;
import com.rheinmetal.tianshu.client.tool.ContainerTransferTool;
import com.rheinmetal.tianshu.client.tool.CraftRecipeTool;
import com.rheinmetal.tianshu.client.tool.LootQueryTool;
import com.rheinmetal.tianshu.client.tool.RecipeQueryTool;
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;
import com.rheinmetal.tianshu.neoforge.body.NeoForgeActionPlayers;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import com.rheinmetal.tianshu.mc.tool.BodyToolRegistry;
import com.rheinmetal.tianshu.mc.host.ServerTicks;
import com.rheinmetal.tianshu.mc.host.ServerPlayerAuthority;
import com.rheinmetal.tianshu.mc.tool.BlockInteractSource;
import com.rheinmetal.tianshu.mc.tool.BlockPlaceSource;
import com.rheinmetal.tianshu.mc.tool.BodyQuerySource;
import com.rheinmetal.tianshu.mc.tool.AgentItemSource;
import com.rheinmetal.tianshu.mc.tool.BlockMachineSource;
import com.rheinmetal.tianshu.mc.tool.ContainerTransferSource;
import com.rheinmetal.tianshu.mc.tool.CraftSource;
import com.rheinmetal.tianshu.mc.tool.LootQuerySource;
import com.rheinmetal.tianshu.mc.tool.ServerRecipeQuerySource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import com.rheinmetal.tianshu.mc.body.BodyCommands;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import com.rheinmetal.tianshu.mc.body.BodyRuntime;
import com.rheinmetal.tianshu.mc.body.BodyPersistence;
import com.rheinmetal.tianshu.client.tool.routing.ActionRouter;
import com.rheinmetal.tianshu.client.tool.routing.CraftRouter;
import com.rheinmetal.tianshu.client.tool.routing.UseItemRouter;
import com.rheinmetal.tianshu.client.tool.routing.InteractRouter;
import com.rheinmetal.tianshu.client.tool.routing.MachineRouter;
import com.rheinmetal.tianshu.client.tool.routing.TransferRouter;
import com.rheinmetal.tianshu.client.tool.routing.ItemRouter;
import com.rheinmetal.tianshu.client.tool.routing.PlaceRouter;
import com.rheinmetal.tianshu.client.tool.routing.QueryRouter;
import com.rheinmetal.tianshu.client.tool.routing.MotionRouter;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * The server half of the tool channel: announces which server-side tools exist and answers calls.
 *
 * <p>It never invents results: every call goes through the same registry and policy checks as a local one, with the
 * caller carrying the requesting player's identity and operator state, so a server tool sees exactly the permission
 * the player has.</p>
 */
public final class ServerToolHandler {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private ServerToolHandler() {
    }

    /** Sends the catalog when a player joins, like the action channel does. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerToolHandler::onPlayerLogin);
        gameBus.addListener(ServerToolHandler::onPlayerLogout);
        gameBus.addListener(ServerToolHandler::onServerStarting);
        gameBus.addListener(ServerToolHandler::onServerStopping);
        gameBus.addListener(ServerToolHandler::onRegisterCommands);
        gameBus.addListener(ServerToolHandler::onServerTick);
    }

    /** Installs the server-side tool sources and registers the tools they back. */
    private static void onServerStarting(ServerStartingEvent event) {
        ActionPlayerFactory actionPlayers = new NeoForgeActionPlayers();
        BodyRegistry bodies = BodyRuntime.start(event.getServer(), actionPlayers);
        TianshuRuntimePorts.register(ServerTickPort.class, ServerTicks.INSTANCE);
        TianshuRuntimePorts.register(PlayerAuthority.class, new ServerPlayerAuthority(event.getServer()));
        ServerTicks.INSTANCE.onNextTick(() -> BodyPersistence.load(event.getServer(), bodies));
        TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(event.getServer()));
        TianshuRuntimePorts.register(RecipeQueryPort.class, new ServerRecipeQuerySource(event.getServer()));
        if (TianshuTools.find(RecipeQueryTool.ID) == null) {
            TianshuTools.register(new RecipeQueryTool());
        }
        TianshuRuntimePorts.register(BlockInteractPort.class,
                new InteractRouter(new BlockInteractSource(event.getServer(), bodies, actionPlayers)));
        TianshuRuntimePorts.register(BlockPlacePort.class,
                new PlaceRouter(new BlockPlaceSource(event.getServer(), bodies, actionPlayers)));
        TianshuRuntimePorts.register(CraftPort.class, new CraftRouter(new CraftSource(event.getServer())));
        TianshuRuntimePorts.register(ContainerTransferPort.class,
                new TransferRouter(new ContainerTransferSource(event.getServer())));
        TianshuRuntimePorts.register(BlockMachinePort.class, new MachineRouter(new BlockMachineSource(event.getServer())));
        TianshuRuntimePorts.register(AgentMotionPort.class, new MotionRouter());
        TianshuRuntimePorts.register(AgentActionPort.class, new ActionRouter());
        TianshuRuntimePorts.register(AgentWorldQueryPort.class, new QueryRouter(new BodyQuerySource(event.getServer())));
        TianshuRuntimePorts.register(AgentItemPort.class, new ItemRouter(new AgentItemSource(event.getServer(), bodies)));
        TianshuRuntimePorts.register(AgentItemUsePort.class, new UseItemRouter());
        if (TianshuTools.find(LootQueryTool.ID) == null) {
            TianshuTools.register(new LootQueryTool());
        }
        if (TianshuTools.find(BlockInteractTool.ID) == null) {
            TianshuTools.register(new BlockInteractTool());
        }
        if (TianshuTools.find(CraftRecipeTool.ID) == null) {
            TianshuTools.register(new CraftRecipeTool());
        }
        TianshuTools.setEnabled(BlockInteractTool.ID, ServerActionConfig.BLOCK_INTERACT.get());
        if (TianshuTools.find(ContainerTransferTool.ID) == null) {
            TianshuTools.register(new ContainerTransferTool());
        }
        TianshuTools.setEnabled(CraftRecipeTool.ID, ServerActionConfig.CRAFT_RECIPE.get());
        if (TianshuTools.find(BlockMachineTool.ID) == null) {
            TianshuTools.register(new BlockMachineTool());
        }
        TianshuTools.setEnabled(ContainerTransferTool.ID, ServerActionConfig.CONTAINER_TRANSFER.get());
        TianshuTools.setEnabled(BlockMachineTool.ID, ServerActionConfig.BLOCK_MACHINE.get());
        registerBodyTools(ServerActionConfig.BODY_ACTIONS.get(), ServerActionConfig.BLOCK_PLACE.get());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        BodyRuntime.current().ifPresent(registry -> BodyPersistence.save(event.getServer(), registry));
        BodyRuntime.stop();
        ServerTicks.INSTANCE.clear();
    }

    /** Registers the body tools; the four world-writing ones share the single {@code body_actions} switch. */
    private static void registerBodyTools(boolean actionsEnabled, boolean placeEnabled) {
        BodyToolRegistry.register(actionsEnabled, placeEnabled);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        ServerTicks.INSTANCE.fire();
        BodyRuntime.current().ifPresent(registry -> BodyPersistence.tick(event.getServer(), registry));
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        BodyCommands.register(event.getDispatcher());
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.connection == null) {
            return;
        }
        String playerId = player.getUUID().toString();
        boolean operator = player.hasPermissions(OPERATOR_PERMISSION_LEVEL);
        // Registering pushes the current catalogue immediately; later changes arrive without the 60s poll.
        ToolCatalogPushRegistry.register(playerId, operator,
                (ignored, ids) -> sendCatalog(player, ids));
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ToolCatalogPushRegistry.unregister(player.getUUID().toString());
        }
    }

    private static void sendCatalog(ServerPlayer player, List<String> ids) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null) {
                PacketDistributor.sendToPlayer(player, new ToolCatalogPacket(ids));
            }
        });
    }

    /** Called on the network thread for one request; the work is moved to the server thread. */
    public static void handle(ToolCallRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> respond(player, packet));
    }
    private static void respond(ServerPlayer player, ToolCallRequestPacket packet) {
        if (player == null) {
            return;
        }
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(), player.hasPermissions(OPERATOR_PERMISSION_LEVEL));
        ServerToolDispatch.respond(caller, packet.requestId(), packet.toolId(), packet.argsJson())
                .thenAccept(reply -> send(player, new ToolCallResultPacket(reply.requestId(), reply.ok(),
                        reply.valueJson(), reply.errorCode(), reply.message())));
    }
    private static void send(ServerPlayer player, ToolCallResultPacket result) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null) {
                PacketDistributor.sendToPlayer(player, result);
            }
        });
    }
}
