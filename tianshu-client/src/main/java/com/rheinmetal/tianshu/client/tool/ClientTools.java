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
        TianshuTools.register(new TagQueryTool());
        TianshuTools.register(new InventoryInspectTool());
        TianshuTools.register(new CapabilityReadTool());
        // Server-side tools are declared here but appear only after the server's catalog names them.
        ServerToolDeclarations.declare(new RecipeQueryTool());
        ServerToolDeclarations.declare(new LootQueryTool());
        ServerToolDeclarations.declare(new BlockInteractTool());
        ServerToolDeclarations.declare(new CraftRecipeTool());
        ServerToolDeclarations.declare(new ContainerTransferTool());
        ServerToolDeclarations.declare(new BlockMachineTool());
        ServerToolDeclarations.declare(new BlockPlaceTool());
        ServerToolDeclarations.declare(new BodyListTool());
        ServerToolDeclarations.declare(new BodyMoveTool());
        ServerToolDeclarations.declare(new BodyLookTool());
        ServerToolDeclarations.declare(new BodyMineTool());
        ServerToolDeclarations.declare(new BodyAttackTool());
        ServerToolDeclarations.declare(new BodyScanTool());
        ServerToolDeclarations.declare(new BodyInventoryTool());
        ServerToolDeclarations.declare(new BodyPickupTool());
        ServerToolDeclarations.declare(new BodyEquipTool());
        ServerToolDeclarations.declare(new BodyCollectTool());
        ServerToolDeclarations.declare(new BodyFollowTool());
        ServerToolDeclarations.declare(new ItemUseTool());
        ServerToolDeclarations.declare(new BodySequenceTool());
    }

    /** Drops the kernel tools and lets a later {@link #register()} run again. Tests only. */
    public static void unregisterForTests() {
        TianshuTools.unregister(TagQueryTool.ID);
        TianshuTools.unregister(InventoryInspectTool.ID);
        TianshuTools.unregister(CapabilityReadTool.ID);
        ServerToolDeclarations.clearForTests();
        REGISTERED.set(false);
    }
}
