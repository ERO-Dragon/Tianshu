package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
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
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.mc.tool.BlockInteractSource;
import com.rheinmetal.tianshu.mc.tool.BlockMachineSource;
import com.rheinmetal.tianshu.mc.tool.ContainerTransferSource;
import com.rheinmetal.tianshu.mc.tool.CraftSource;
import com.rheinmetal.tianshu.mc.tool.LootQuerySource;
import com.rheinmetal.tianshu.mc.tool.ServerRecipeQuerySource;
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * The server half of the tool channel: announces the server-side tools that exist here and answers calls.
 *
 * <p>This generation has no recipe/display access that would let craft.recipe read a recipe's ingredients, and its
 * fuel API differs from 1.21.1, so this line offers loot.query, block.interact and container.transfer only.</p>
 */
public final class ServerToolHandler {

    private ServerToolHandler() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerToolHandler::onPlayerLogin);
        gameBus.addListener(ServerToolHandler::onPlayerLogout);
        gameBus.addListener(ServerToolHandler::onServerStarting);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        TianshuRuntimePorts.register(LootQueryPort.class, new LootQuerySource(event.getServer()));
        TianshuRuntimePorts.register(RecipeQueryPort.class, new ServerRecipeQuerySource(event.getServer()));
        if (TianshuTools.find(RecipeQueryTool.ID) == null) {
            TianshuTools.register(new RecipeQueryTool());
        }
        TianshuRuntimePorts.register(BlockInteractPort.class, new BlockInteractSource(event.getServer()));
        TianshuRuntimePorts.register(ContainerTransferPort.class,
                new ContainerTransferSource(event.getServer()));
        TianshuRuntimePorts.register(CraftPort.class, new CraftSource(event.getServer()));
        TianshuRuntimePorts.register(BlockMachinePort.class, new BlockMachineSource(event.getServer()));
        if (TianshuTools.find(CraftRecipeTool.ID) == null) {
            TianshuTools.register(new CraftRecipeTool());
        }
        if (TianshuTools.find(BlockMachineTool.ID) == null) {
            TianshuTools.register(new BlockMachineTool());
        }
        TianshuTools.setEnabled(CraftRecipeTool.ID, ServerActionConfig.CRAFT_RECIPE.get());
        TianshuTools.setEnabled(BlockMachineTool.ID, ServerActionConfig.BLOCK_MACHINE.get());
        register(new LootQueryTool());
        register(new BlockInteractTool());
        register(new ContainerTransferTool());
        TianshuTools.setEnabled(BlockInteractTool.ID, ServerActionConfig.BLOCK_INTERACT.get());
        TianshuTools.setEnabled(ContainerTransferTool.ID, ServerActionConfig.CONTAINER_TRANSFER.get());
    }

    private static void register(com.rheinmetal.tianshu.api.tool.TianshuTool<?, ?> tool) {
        if (TianshuTools.find(tool.id()) == null) {
            TianshuTools.register(tool);
        }
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.connection == null) {
            return;
        }
        String playerId = player.getUUID().toString();
        boolean operator = player.permissions()
                .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
        ToolCatalogPushRegistry.register(playerId, operator, (ignored, ids) -> sendCatalog(player, ids));
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
        ToolCaller caller = ToolCaller.player(player.getUUID().toString(), player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR));
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
