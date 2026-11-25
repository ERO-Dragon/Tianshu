package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.api.entity.EntitySpawnPort;
import com.rheinmetal.tianshu.mc.tool.ServerToolSession;
import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.EntityUsePort;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.assembly.PlayerAuthority;
import com.rheinmetal.tianshu.api.game.ActorStatusPort;
import com.rheinmetal.tianshu.api.game.BlockBreakPort;
import com.rheinmetal.tianshu.api.game.BlockInspectPort;
import com.rheinmetal.tianshu.api.game.EntityInspectPort;
import com.rheinmetal.tianshu.api.game.ItemInspectPort;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.game.RecipeAvailabilityPort;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import com.rheinmetal.tianshu.api.game.WorldInfoPort;
import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import com.rheinmetal.tianshu.api.tool.ServerAutonomyCeiling;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ServerToolDispatch;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.server.ServerActionExecutionPort;
import com.rheinmetal.tianshu.api.tool.ToolCatalogPushRegistry;
import com.rheinmetal.tianshu.client.tool.BlockBreakTool;
import com.rheinmetal.tianshu.client.tool.BlockInspectTool;
import com.rheinmetal.tianshu.client.tool.EntityInspectTool;
import com.rheinmetal.tianshu.client.tool.ItemInspectTool;
import com.rheinmetal.tianshu.client.tool.BodyStatusTool;
import com.rheinmetal.tianshu.client.tool.BlockInteractTool;
import com.rheinmetal.tianshu.client.tool.BlockMachineTool;
import com.rheinmetal.tianshu.client.tool.ContainerTransferTool;
import com.rheinmetal.tianshu.client.tool.CraftAvailableTool;
import com.rheinmetal.tianshu.client.tool.CraftRecipeTool;
import com.rheinmetal.tianshu.client.tool.LootQueryTool;
import com.rheinmetal.tianshu.client.tool.RecipeQueryTool;
import com.rheinmetal.tianshu.client.tool.WorldInfoTool;
import com.rheinmetal.tianshu.client.tool.WorldLocateTool;
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;
import com.rheinmetal.tianshu.neoforge.body.NeoForgeActionPlayers;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.level.GameRules;
import com.rheinmetal.tianshu.mc.tool.BodyToolRegistry;
import com.rheinmetal.tianshu.mc.tool.WorldInfoSource;
import com.rheinmetal.tianshu.mc.tool.WorldLocateSource;
import com.rheinmetal.tianshu.mc.body.BlockBreakSource;
import com.rheinmetal.tianshu.mc.tool.ActorStatusSource;
import com.rheinmetal.tianshu.mc.tool.CraftAvailableSource;
import com.rheinmetal.tianshu.mc.tool.BlockInspectSource;
import com.rheinmetal.tianshu.mc.tool.EntityInspectSource;
import com.rheinmetal.tianshu.mc.tool.ItemInspectSource;
import com.rheinmetal.tianshu.neoforge.integration.NeoForgeBlockStorageProbe;
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
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import com.rheinmetal.tianshu.mc.body.BodyCommands;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import com.rheinmetal.tianshu.mc.body.BodyRuntime;
import com.rheinmetal.tianshu.client.tool.routing.ActionRouter;
import com.rheinmetal.tianshu.client.tool.routing.CraftRouter;
import com.rheinmetal.tianshu.client.tool.routing.UseItemRouter;
import com.rheinmetal.tianshu.client.tool.routing.EntityUseRouter;
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

    /** What the current server start installed, so the stop can take exactly that back. */
    private static final ServerToolSession SESSION = new ServerToolSession();

    /** Handles a config reload only while this process is serving, because only then are these tools registered. */
    private static final SwitchReload SWITCH_RELOAD = new SwitchReload();

    private static IEventBus modBus;

    private ServerToolHandler() {
    }

    /** Sends the catalog when a player joins, like the action channel does. */
    public static void register(IEventBus gameBus, IEventBus modBus) {
        ServerToolHandler.modBus = modBus;
        gameBus.addListener(ServerToolHandler::onPlayerLogin);
        gameBus.addListener(ServerToolHandler::onPlayerLogout);
        gameBus.addListener(ServerToolHandler::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerToolHandler::onLivingDeath);
        gameBus.addListener(ServerToolHandler::onServerStopping);
        gameBus.addListener(ServerToolHandler::onServerStarted);
        gameBus.addListener(ServerToolHandler::onRegisterCommands);
        gameBus.addListener(ServerToolHandler::onServerTick);
    }

    /** Installs the server-side tool sources and registers the tools they back. */
    private static void onLivingDeath(LivingDeathEvent event) {
        BodyRuntime.current().ifPresent(bodies -> {
            boolean keep = event.getEntity().level().getGameRules()
                    .getBoolean(GameRules.RULE_KEEPINVENTORY);
            if (bodies.onDeath(event.getEntity(), keep)) {
                event.setCanceled(true);
            }
        });
    }

    private static void onServerStarting(ServerStartingEvent event) {
        SESSION.start(registrations -> {
            ActionPlayerFactory actionPlayers = new NeoForgeActionPlayers();
            // One probe for the whole session: the block side of inspect and transfer must resolve the same store.
            com.rheinmetal.tianshu.mc.tool.BlockStorageProbe blockProbe =
                    new com.rheinmetal.tianshu.neoforge.integration.NeoForgeBlockStorageProbe();
            BodyRegistry bodies = BodyRuntime.start(event.getServer(), actionPlayers,
                    new com.rheinmetal.tianshu.neoforge.NeoForgePlayerBodies(), blockProbe);
            registrations.port(EntitySpawnPort.class, bodies);
            registrations.port(ServerTickPort.class, ServerTicks.INSTANCE);
            registrations.port(PlayerAuthority.class, new ServerPlayerAuthority(event.getServer()));
            // The operator's ceiling on server-side tools; read live from the config so a reload takes effect.
            registrations.port(ServerAutonomyCeiling.class, ServerActionConfig::autonomyCeiling);
            // The cheat-grade tools are server tools: the server owns their implementation and executes them here.
            registrations.port(ServerActionExecutionPort.class,
                    new NeoForgeActionExecution(event.getServer()));
            // What this server will actually allow, and from which file: the one line that settles "I edited it and
            // nothing changed".
            com.mojang.logging.LogUtils.getLogger().info("tianshu server config: file={} {}",
                    net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("tianshu_ferry-server.toml"),
                    ServerActionConfig.describe());
            for (var actionTool : com.rheinmetal.tianshu.client.tool.ServerActionTools.tools()) {
                registrations.tool(actionTool);
            }
            // Only after registration: the advertised state belongs to a tool that exists in this registry.
            com.rheinmetal.tianshu.client.tool.ServerActionTools.applySwitches(
                    action -> { var value = ServerActionConfig.switchFor(action); return value != null && value.get(); });
            ServerTicks.INSTANCE.onNextTick(() -> bodies.persistence().load(event.getServer(), bodies));
            registrations.port(LootQueryPort.class, new LootQuerySource(event.getServer()));
            registrations.port(RecipeQueryPort.class, new ServerRecipeQuerySource(event.getServer()));
            registrations.tool(new RecipeQueryTool());
            registrations.port(BlockInteractPort.class,
                    new InteractRouter(new BlockInteractSource(event.getServer(), bodies, actionPlayers)));
            registrations.port(BlockPlacePort.class,
                    new PlaceRouter(new BlockPlaceSource(event.getServer(), bodies, actionPlayers)));
            registrations.port(CraftPort.class, new CraftRouter(new CraftSource(event.getServer())));
            registrations.port(ContainerTransferPort.class,
                    new TransferRouter(new ContainerTransferSource(event.getServer(), blockProbe)));
            registrations.port(BlockMachinePort.class, new MachineRouter(new BlockMachineSource(event.getServer())));
            registrations.port(BlockBreakPort.class, new BlockBreakSource(event.getServer(), bodies, new ActionRouter()));
            registrations.port(EntityInspectPort.class, new EntityInspectSource(event.getServer()));
            registrations.port(ItemInspectPort.class, new ItemInspectSource(event.getServer()));
            registrations.port(BlockInspectPort.class,
                    new BlockInspectSource(event.getServer(), blockProbe));
            registrations.port(ActorStatusPort.class, new ActorStatusSource(event.getServer()));
            registrations.port(WorldInfoPort.class, new WorldInfoSource(event.getServer()));
            registrations.port(WorldLocatePort.class, new WorldLocateSource(event.getServer()));
            registrations.port(RecipeAvailabilityPort.class, new CraftAvailableSource(event.getServer()));
            registrations.tool(new BlockBreakTool());
            registrations.tool(new BlockInspectTool());
            registrations.tool(new EntityInspectTool());
            registrations.tool(new ItemInspectTool());
            registrations.tool(new BodyStatusTool());
            registrations.tool(new WorldInfoTool());
            registrations.tool(new WorldLocateTool());
            registrations.tool(new CraftAvailableTool());
            registrations.port(EntityMotionPort.class, new MotionRouter());
            registrations.port(EntityActionPort.class, new ActionRouter());
            registrations.port(EntityQueryPort.class, new QueryRouter(new BodyQuerySource(event.getServer())));
            registrations.port(EntityItemPort.class, new ItemRouter(new AgentItemSource(event.getServer(), bodies)));
            registrations.port(EntityItemUsePort.class, new UseItemRouter());
            registrations.port(EntityUsePort.class, new EntityUseRouter());
            registrations.tool(new LootQueryTool());
            registrations.tool(new BlockInteractTool());
            registrations.tool(new CraftRecipeTool());
            TianshuTools.setEnabled(BlockInteractTool.ID, ServerActionConfig.BLOCK_INTERACT.get());
            registrations.tool(new ContainerTransferTool());
            TianshuTools.setEnabled(CraftRecipeTool.ID, ServerActionConfig.CRAFT_RECIPE.get());
            registrations.tool(new BlockMachineTool());
            TianshuTools.setEnabled(ContainerTransferTool.ID, ServerActionConfig.CONTAINER_TRANSFER.get());
            TianshuTools.setEnabled(BlockMachineTool.ID, ServerActionConfig.BLOCK_MACHINE.get());
            registerBodyTools(ServerActionConfig.BODY_ACTIONS.get(), ServerActionConfig.BLOCK_PLACE.get());
        });
        // Now that the switch has a table to act on, a reload can keep it honest. The listener lives exactly as long as
        // the session: on a client connected to a server the same config file is loaded, and flipping the state of a
        // tool this process never registered is an error, not a no-op.
        if (modBus != null) {
            modBus.register(SWITCH_RELOAD);
        }
    }


    /**
     * Reports declarations that cannot work, once modules have published what they publish.
     *
     * <p>At "started", not at "starting": modules declare their capabilities while they load, and auditing earlier
     * would call every provider "not installed". A session with nothing wrong logs nothing.</p>
     */
    private static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        var logger = com.mojang.logging.LogUtils.getLogger();
        com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions.report(problem -> logger.warn("tianshu: {}", problem));
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (modBus != null) {
            modBus.unregister(SWITCH_RELOAD);
        }
        SESSION.stop(event.getServer());
    }

    /** A switch edited while the server runs changes what the catalogue advertises, or the client keeps a dead tool. */
    public static final class SwitchReload {

        @net.neoforged.bus.api.SubscribeEvent
        public void onReload(net.neoforged.fml.event.config.ModConfigEvent.Reloading event) {
            com.rheinmetal.tianshu.client.tool.ServerActionTools.applySwitches(action -> {
                var value = ServerActionConfig.switchFor(action);
                return value != null && value.get();
            });
        }
    }

    /** Registers the body tools; the four world-writing ones share the single {@code body_actions} switch. */
    private static void registerBodyTools(boolean actionsEnabled, boolean placeEnabled) {
        BodyToolRegistry.register(actionsEnabled, placeEnabled);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        ServerTicks.INSTANCE.fire();
        BodyRuntime.current().ifPresent(registry -> {
                // Rebind unloaded carriers before the timer can save a registry that lost them.
                registry.refreshCarriers(event.getServer());
                registry.persistence().tick(event.getServer(), registry);
            });
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        BodyCommands.register(event.getDispatcher());
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.connection == null
                || !player.connection.hasChannel(ToolCatalogPacket.TYPE)) {
            return;
        }
        String playerId = player.getUUID().toString();
        boolean operator = player.hasPermissions(OPERATOR_PERMISSION_LEVEL);
        // Registering pushes the current catalogue immediately; later changes arrive without the 60s poll.
        ToolCatalogPushRegistry.register(playerId, operator,
                (ignored, tools) -> sendCatalog(player, tools));
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String playerId = player.getUUID().toString();
            ToolCatalogPushRegistry.unregister(playerId);
            // The throttle is keyed by (tool, caller); without this the buckets of every player who ever called a
            // tool stay for the life of the process.
            TianshuTools.clearRateLimits(playerId);
        }
    }

    private static void sendCatalog(ServerPlayer player,
                                    List<com.rheinmetal.tianshu.api.tool.ToolCatalogEntry> tools) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null && player.connection.hasChannel(ToolCatalogPacket.TYPE)) {
                PacketDistributor.sendToPlayer(player, new ToolCatalogPacket(tools));
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
                .whenComplete((reply, failure) -> {
                    if (reply != null) {
                        send(player, new ToolCallResultPacket(reply.requestId(), reply.ok(),
                                reply.valueJson(), reply.errorCode(), reply.message()));
                    } else if (failure != null) {
                        // Answer instead of staying silent: a dropped reply arrives at the client as a timeout,
                        // which blames the network for a server-side failure.
                        send(player, new ToolCallResultPacket(packet.requestId(), false, "",
                                com.rheinmetal.tianshu.api.tool.ToolError.FAILED.name(),
                                "the server failed to run the tool"));
                    }
                });
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
