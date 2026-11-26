package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.client.tool.BodyGiveTool;
import com.rheinmetal.tianshu.client.tool.BodyAttackTool;
import com.rheinmetal.tianshu.client.tool.BodyEquipTool;
import com.rheinmetal.tianshu.client.tool.BodyDropTool;
import com.rheinmetal.tianshu.client.tool.EntityUseTool;
import com.rheinmetal.tianshu.client.tool.BodyItemUseTool;
import com.rheinmetal.tianshu.client.tool.BodyPlaceTool;
import com.rheinmetal.tianshu.client.tool.BodyPlaceLineTool;
import com.rheinmetal.tianshu.client.tool.BodySequenceTool;
import com.rheinmetal.tianshu.client.tool.BodyInventoryTool;
import com.rheinmetal.tianshu.client.tool.BodyListTool;
import com.rheinmetal.tianshu.client.tool.BodyLookTool;
import com.rheinmetal.tianshu.client.tool.BodyMineTool;
import com.rheinmetal.tianshu.client.tool.BodyMoveTool;
import com.rheinmetal.tianshu.client.tool.BodyPickupTool;
import com.rheinmetal.tianshu.client.tool.BodyScanTool;

import java.util.function.Supplier;

/**
 * Registers the agent-body tools once for both loaders.
 *
 * <p>Everything here is on: these are a module's own entity doing what that module declared it can do, and a server
 * switch that could deny it would make the base the owner of someone else's mod. Who may drive a body is settled per
 * call by ownership, and what a body can do is settled by the capabilities the module registered it with.</p>
 */
public final class BodyToolRegistry {

    private BodyToolRegistry() {
    }

    public static void register() {
        ensure(BodyListTool.ID, BodyListTool::new);
        ensure(BodyMoveTool.ID, BodyMoveTool::new);
        ensure(BodyLookTool.ID, BodyLookTool::new);
        ensure(BodyMineTool.ID, BodyMineTool::new);
        ensure(BodyAttackTool.ID, BodyAttackTool::new);
        ensure(BodyPickupTool.ID, BodyPickupTool::new);
        ensure(BodyEquipTool.ID, BodyEquipTool::new);
        ensure(BodyDropTool.ID, BodyDropTool::new);
        ensure(BodyGiveTool.ID, BodyGiveTool::new);
        ensure(BodyItemUseTool.ID, BodyItemUseTool::new);
        ensure(BodyPlaceTool.ID, BodyPlaceTool::new);
        ensure(BodyPlaceLineTool.ID, BodyPlaceLineTool::new);
        ensure(BodySequenceTool.ID, BodySequenceTool::new);
        ensure(EntityUseTool.ID, EntityUseTool::new);
        ensure(BodyScanTool.ID, BodyScanTool::new);
        ensure(BodyInventoryTool.ID, BodyInventoryTool::new);
    }

    private static void ensure(ToolId id, Supplier<TianshuTool<?, ?>> factory) {
        if (TianshuTools.find(id) == null) {
            TianshuTools.registerBuiltin(factory.get());
        }
    }
}
