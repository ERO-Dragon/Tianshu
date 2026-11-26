package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;

import java.util.function.Consumer;

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
        forEachBuiltin(TianshuTools::registerBuiltin, ServerToolDeclarations::declareBuiltin);
    }

    static void forEachBuiltin(Consumer<TianshuTool<?, ?>> local, Consumer<TianshuTool<?, ?>> server) {
        local.accept(new TagQueryTool());
        local.accept(new InventoryInspectTool());
        local.accept(new ContainerInspectTool());
        // Client preferences are local and non-cheat: they change this player's own screen and ears, on by default.
        local.accept(new AudioListTool());
        local.accept(new AudioSetTool());
        local.accept(new PackListTool());
        local.accept(new PackSetTool());
        local.accept(new CapabilityReadTool());
        // Speaking is a local act; the server actions are declarations the server owns the implementations for.
        local.accept(new SayTool());
        for (var actionTool : ServerActionTools.tools()) {
            server.accept(actionTool);
        }
        // Server-side tools are declared here but appear only after the server's catalog names them.
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
