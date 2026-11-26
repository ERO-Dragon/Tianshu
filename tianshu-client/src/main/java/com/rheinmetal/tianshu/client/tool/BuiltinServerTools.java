package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTool;

import java.util.function.Consumer;

/** Built-in server tools used both for client declarations and authoritative registration. */
public final class BuiltinServerTools {
    private BuiltinServerTools() {
    }

    public static void forEach(Consumer<TianshuTool<?, ?>> server) {
        for (var actionTool : ServerActionTools.tools()) {
            server.accept(actionTool);
        }
        server.accept(new RecipeQueryTool());
        server.accept(new LootQueryTool());
        server.accept(new BlockInteractTool());
        server.accept(new BodyInteractTool());
        server.accept(new CraftRecipeTool());
        server.accept(new BodyCraftTool());
        server.accept(new ContainerTransferTool());
        server.accept(new BodyTransferTool());
        server.accept(new BlockMachineTool());
        server.accept(new BodyMachineTool());
        server.accept(new BlockInspectTool());
        server.accept(new BodyBlockInspectTool());
        server.accept(new CommandRunTool());
        server.accept(new BodyCommandRunTool());
        server.accept(new EntityInspectTool());
        server.accept(new ItemInspectTool());
        server.accept(new BodyItemInspectTool());
        server.accept(new BodyStatusTool());
        server.accept(new WorldInfoTool());
        server.accept(new BodyWorldInfoTool());
        WorldLocateTool.tools().forEach(server);
        server.accept(new CraftAvailableTool());
        server.accept(new BodyCraftAvailableTool());
        server.accept(new BodyPlaceTool());
        server.accept(new BodyPlaceLineTool());
        server.accept(new BodyListTool());
        server.accept(new BodyMoveTool());
        server.accept(new BodyLookTool());
        server.accept(new BodyMineTool());
        server.accept(new BodyAttackTool());
        server.accept(new BodyScanTool());
        server.accept(new BodyInventoryTool());
        server.accept(new BodyPickupTool());
        server.accept(new BodyEquipTool());
        server.accept(new BodyDropTool());
        server.accept(new BodyGiveTool());
        server.accept(new EntityUseTool());
        server.accept(new BodyItemUseTool());
        server.accept(new BodySequenceTool());
    }
}
