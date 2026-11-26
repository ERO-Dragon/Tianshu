package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTools;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers the kernel's default tools once per process.
 *
 * <p>Registration lives in the client module because the tools are client capabilities and must be available
 * before any world session exists; the platform only has to call {@link #register()} from its client boot. Tools
 * whose data source is missing on a platform still register: the call reports {@code UNAVAILABLE} with an
 * explanation, which is more useful than the tool silently not existing.</p>
 */
public final class ClientTools {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ClientTools() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        TianshuTools.registerBuiltin(new TagQueryTool());
        TianshuTools.registerBuiltin(new InventoryInspectTool());
        TianshuTools.registerBuiltin(new ContainerInspectTool());
        // Client preferences are local and non-cheat: they change this player's own screen and ears, on by default.
        TianshuTools.registerBuiltin(new AudioListTool());
        TianshuTools.registerBuiltin(new AudioSetTool());
        TianshuTools.registerBuiltin(new PackListTool());
        TianshuTools.registerBuiltin(new PackSetTool());
        TianshuTools.registerBuiltin(new CapabilityReadTool());
        // Speaking is a local act; the server actions are declarations the server owns the implementations for.
        TianshuTools.registerBuiltin(new SayTool());
        for (var actionTool : ServerActionTools.tools()) {
            ServerToolDeclarations.declareBuiltin(actionTool);
        }
        // Server-side tools are declared here but appear only after the server's catalog names them.
        ServerToolDeclarations.declareBuiltin(new RecipeQueryTool());
        ServerToolDeclarations.declareBuiltin(new LootQueryTool());
        ServerToolDeclarations.declareBuiltin(new BlockInteractTool());
        ServerToolDeclarations.declareBuiltin(new BodyInteractTool());
        ServerToolDeclarations.declareBuiltin(new CraftRecipeTool());
        ServerToolDeclarations.declareBuiltin(new BodyCraftTool());
        ServerToolDeclarations.declareBuiltin(new ContainerTransferTool());
        ServerToolDeclarations.declareBuiltin(new BodyTransferTool());
        ServerToolDeclarations.declareBuiltin(new BlockMachineTool());
        ServerToolDeclarations.declareBuiltin(new BodyMachineTool());
        ServerToolDeclarations.declareBuiltin(new BlockInspectTool());
        ServerToolDeclarations.declareBuiltin(new BodyBlockInspectTool());
        ServerToolDeclarations.declareBuiltin(new CommandRunTool());
        ServerToolDeclarations.declareBuiltin(new BodyCommandRunTool());
        ServerToolDeclarations.declareBuiltin(new EntityInspectTool());
        ServerToolDeclarations.declareBuiltin(new ItemInspectTool());
        ServerToolDeclarations.declareBuiltin(new BodyItemInspectTool());
        ServerToolDeclarations.declareBuiltin(new BodyStatusTool());
        ServerToolDeclarations.declareBuiltin(new WorldInfoTool());
        ServerToolDeclarations.declareBuiltin(new BodyWorldInfoTool());
        WorldLocateTool.tools().forEach(ServerToolDeclarations::declareBuiltin);
        ServerToolDeclarations.declareBuiltin(new CraftAvailableTool());
        ServerToolDeclarations.declareBuiltin(new BodyCraftAvailableTool());
        ServerToolDeclarations.declareBuiltin(new BodyPlaceTool());
        ServerToolDeclarations.declareBuiltin(new BodyPlaceLineTool());
        ServerToolDeclarations.declareBuiltin(new BodyListTool());
        ServerToolDeclarations.declareBuiltin(new BodyMoveTool());
        ServerToolDeclarations.declareBuiltin(new BodyLookTool());
        ServerToolDeclarations.declareBuiltin(new BodyMineTool());
        ServerToolDeclarations.declareBuiltin(new BodyAttackTool());
        ServerToolDeclarations.declareBuiltin(new BodyScanTool());
        ServerToolDeclarations.declareBuiltin(new BodyInventoryTool());
        ServerToolDeclarations.declareBuiltin(new BodyPickupTool());
        ServerToolDeclarations.declareBuiltin(new BodyEquipTool());
        ServerToolDeclarations.declareBuiltin(new BodyDropTool());
        ServerToolDeclarations.declareBuiltin(new BodyGiveTool());
        ServerToolDeclarations.declareBuiltin(new EntityUseTool());
        ServerToolDeclarations.declareBuiltin(new BodyItemUseTool());
        ServerToolDeclarations.declareBuiltin(new BodySequenceTool());
    }

    /** Drops the kernel tools and lets a later {@link #register()} run again. Tests only. */
    public static void unregisterForTests() {
        TianshuTools.unregister(TagQueryTool.ID);
        TianshuTools.unregister(InventoryInspectTool.ID);
        TianshuTools.unregister(ContainerInspectTool.ID);
        TianshuTools.unregister(AudioListTool.ID);
        TianshuTools.unregister(AudioSetTool.ID);
        TianshuTools.unregister(PackListTool.ID);
        TianshuTools.unregister(PackSetTool.ID);
        TianshuTools.unregister(CapabilityReadTool.ID);
        ServerActionTools.ids().forEach(TianshuTools::unregister);
        TianshuTools.unregister(SayTool.ID);
        ServerToolDeclarations.clearForTests();
        REGISTERED.set(false);
    }
}
