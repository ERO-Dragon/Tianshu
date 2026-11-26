package com.rheinmetal.tianshu.fabric;

import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import com.rheinmetal.tianshu.api.entity.EntitySpawnPort;
import com.rheinmetal.tianshu.mc.tool.ActorStatusSource;
import com.rheinmetal.tianshu.mc.tool.CraftAvailableSource;
import com.rheinmetal.tianshu.mc.tool.BlockInspectSource;
import com.rheinmetal.tianshu.mc.tool.EntityInspectSource;
import com.rheinmetal.tianshu.mc.tool.ItemInspectSource;
import com.rheinmetal.tianshu.mc.tool.ServerToolSession;
import com.rheinmetal.tianshu.mc.tool.WorldInfoSource;
import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.EntityUsePort;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.assembly.PlayerAuthority;
import com.rheinmetal.tianshu.api.game.ActorStatusPort;
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
import com.rheinmetal.tianshu.api.tool.ServerAutonomyCeiling;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ServerToolDispatch;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.server.ServerActionExecutionPort;
import com.rheinmetal.tianshu.api.tool.ToolCatalogPushRegistry;
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
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;

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
import com.rheinmetal.tianshu.mc.body.BodyCommands;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import com.rheinmetal.tianshu.mc.body.BodyRuntime;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.world.level.GameRules;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The server half of the tool channel: announces which server-side tools exist and answers calls.
 *
 * <p>Every call goes through the same registry and policy checks as a local one, with the caller carrying the
 * requesting player's identity and operator state.</p>
 */
public final class FabricServerToolHandler {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    /** What the current server start installed, so the stop can take exactly that back. */
    private static final ServerToolSession SESSION = new ServerToolSession();

    private FabricServerToolHandler() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> BodyRuntime.current()
                .map(bodies -> !bodies.onDeath(entity,
                        entity.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)))
                .orElse(true));
        // Declarations that cannot work are reported once modules have published; an empty audit logs nothing.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> com.rheinmetal.tianshu.api.capability.CapabilityDiagnostics
                .report(problem -> org.slf4j.LoggerFactory.getLogger("tianshu").warn("tianshu: {}", problem)));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> SESSION.start(server, registrations -> {
            ActionPlayerFactory actionPlayers = new FabricActionPlayers();
            // One probe for the whole session: the block side of inspect and transfer must resolve the same store.
            com.rheinmetal.tianshu.mc.tool.BlockStorageProbe blockProbe = new FabricBlockStorageProbe();
            BodyRegistry bodies = BodyRuntime.start(server, actionPlayers, new FabricPlayerBodies(), blockProbe);
            registrations.port(EntitySpawnPort.class, bodies);
            registrations.port(ServerTickPort.class, ServerTicks.INSTANCE);
            registrations.port(PlayerAuthority.class, new ServerPlayerAuthority(server));
            ServerTicks.INSTANCE.onNextTick(() -> bodies.persistence().load(server, bodies));
            FabricToolConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
            // The operator's ceiling on server-side tools, read from the same server config as the action switches.
            registrations.port(ServerAutonomyCeiling.class, FabricServerActionConfig::autonomyCeiling);
            // The cheat-grade tools are server tools: the server owns their implementation and executes them here.
            registrations.port(ServerActionExecutionPort.class, new FabricActionExecution(server));
            for (var actionTool : com.rheinmetal.tianshu.client.tool.ServerActionTools.tools()) {
                registrations.tool(actionTool);
            }
            // A tool's advertised state follows its own action switch, or the client keeps offering a dead tool.
            com.rheinmetal.tianshu.client.tool.ServerActionTools.applySwitches(FabricServerActionConfig::enabled);
            registrations.port(LootQueryPort.class, new LootQuerySource(server));
            registrations.port(RecipeQueryPort.class, new ServerRecipeQuerySource(server));
            registrations.tool(new RecipeQueryTool());
            registrations.port(BlockInteractPort.class,
                    new InteractRouter(new BlockInteractSource(server, bodies, actionPlayers)));
            registrations.port(BlockPlacePort.class,
                    new PlaceRouter(new BlockPlaceSource(server, bodies, actionPlayers)));
            registrations.port(CraftPort.class, new CraftRouter(new CraftSource(server)));
            registrations.port(ContainerTransferPort.class, new TransferRouter(new ContainerTransferSource(server, blockProbe)));
            registrations.port(BlockMachinePort.class, new MachineRouter(new BlockMachineSource(server)));
            registrations.port(EntityInspectPort.class, new EntityInspectSource(server));
            registrations.port(ItemInspectPort.class, new ItemInspectSource(server));
            registrations.port(BlockInspectPort.class,
                    new BlockInspectSource(server, blockProbe));
            registrations.port(ActorStatusPort.class, new ActorStatusSource(server));
            registrations.port(WorldInfoPort.class, new WorldInfoSource(server));
            registrations.port(RecipeAvailabilityPort.class, new CraftAvailableSource(server));
            registrations.tool(new BlockInspectTool());
            registrations.tool(new EntityInspectTool());
            registrations.tool(new ItemInspectTool());
            registrations.tool(new BodyStatusTool());
            registrations.tool(new WorldInfoTool());
            registrations.tool(new CraftAvailableTool());
            registrations.port(EntityMotionPort.class, new MotionRouter());
            registrations.port(EntityActionPort.class, new ActionRouter());
            registrations.port(EntityQueryPort.class, new QueryRouter(new BodyQuerySource(server)));
            registrations.port(EntityItemPort.class, new ItemRouter(new AgentItemSource(server, bodies)));
            registrations.port(EntityItemUsePort.class, new UseItemRouter());
            registrations.port(EntityUsePort.class, new EntityUseRouter());
            registrations.tool(new LootQueryTool());
            registrations.tool(new BlockInteractTool());
            registrations.tool(new CraftRecipeTool());
            registrations.tool(new ContainerTransferTool());
            registrations.tool(new BlockMachineTool());
            BodyToolRegistry.register();
            // Asking is open to anyone; running needs one operator approval per command, so the tool is not
            // operator-only — an operator-only tool could never be asked for.
            registrations.port(com.rheinmetal.tianshu.api.game.CommandExecutionPort.class,
                    new com.rheinmetal.tianshu.mc.command.CommandExecutionSource(server,
                            com.rheinmetal.tianshu.api.game.CommandApprovals.shared(),
                            FabricToolConfig::commandPermissionLevel));
            registrations.tool(new com.rheinmetal.tianshu.client.tool.CommandRunTool());
            TianshuTools.setEnabled(com.rheinmetal.tianshu.client.tool.CommandRunTool.ID,
                    FabricToolConfig.commandRun());
        }));
        ServerPlayNetworking.registerGlobalReceiver(ToolCallRequestPacket.TYPE, (packet, context) ->
                context.server().execute(() -> respond(context.player(), packet)));
        // Fabric also permits peers to announce play channels after JOIN.
        S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(ToolCatalogPacket.TYPE.id())) {
                onPlayerLogin(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerLogin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onPlayerLogout(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // An approval belongs to the session that asked for it; the next one must not start pre-approved.
            com.rheinmetal.tianshu.api.game.CommandApprovals.shared().clear();
            SESSION.stop(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SESSION.tick();
            BodyRuntime.current().ifPresent(registry -> {
                // Rebind unloaded carriers before the timer can save a registry that lost them.
                registry.refreshCarriers(server);
                registry.persistence().tick(server, registry);
            });
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            BodyCommands.register(dispatcher);
            // The human half of command.run: only this creates an approval, and it needs permission level 2.
            com.rheinmetal.tianshu.mc.command.TianshuCommandApprovalCommands.register(dispatcher,
                    com.rheinmetal.tianshu.api.game.CommandApprovals.shared());
        });
    }


    private static void onPlayerLogin(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, ToolCatalogPacket.TYPE)) {
            return;
        }
        String playerId = player.getUUID().toString();
        boolean operator = player.hasPermissions(OPERATOR_PERMISSION_LEVEL);
        ToolCatalogPushRegistry.register(playerId, operator, (ignored, tools) -> sendCatalog(player, tools));
    }

    private static void onPlayerLogout(ServerPlayer player) {
        if (player != null) {
            String playerId = player.getUUID().toString();
            ToolCatalogPushRegistry.unregister(playerId);
            // The throttle is keyed by (tool, caller); without this every player who ever called a tool keeps a
            // bucket for the life of the process.
            TianshuTools.clearRateLimits(playerId);
        }
    }

    private static void sendCatalog(ServerPlayer player,
                                    List<com.rheinmetal.tianshu.api.tool.ToolCatalogEntry> tools) {
        var server = player.level().getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null && ServerPlayNetworking.canSend(player, ToolCatalogPacket.TYPE)) {
                ServerPlayNetworking.send(player, new ToolCatalogPacket(tools));
            }
        });
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
                        // Answer instead of staying silent: a dropped reply reaches the client as a timeout, which
                        // blames the network for a server-side failure.
                        send(player, new ToolCallResultPacket(packet.requestId(), false, "",
                                com.rheinmetal.tianshu.api.tool.ToolError.FAILED.name(),
                                "the server failed to run the tool"));
                    }
                });
    }
    private static void send(ServerPlayer player, ToolCallResultPacket result) {
        if (player.connection == null || !ServerPlayNetworking.canSend(player, ToolCallResultPacket.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, result);
    }
}
