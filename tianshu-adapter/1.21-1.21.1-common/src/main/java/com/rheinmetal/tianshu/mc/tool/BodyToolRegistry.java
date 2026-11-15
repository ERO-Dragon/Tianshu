package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.client.tool.BlockPlaceTool;
import com.rheinmetal.tianshu.client.tool.BodyAttackTool;
import com.rheinmetal.tianshu.client.tool.BodyInventoryTool;
import com.rheinmetal.tianshu.client.tool.BodyListTool;
import com.rheinmetal.tianshu.client.tool.BodyLookTool;
import com.rheinmetal.tianshu.client.tool.BodyMineTool;
import com.rheinmetal.tianshu.client.tool.BodyMoveTool;
import com.rheinmetal.tianshu.client.tool.BodyScanTool;

import java.util.function.Supplier;

/**
 * Registers the agent-body tools once for both loaders, so a loader handler only supplies the switch values.
 *
 * <p>{@code body.list} is a read tool and always available; the four world-writing ones share one switch and stay
 * off until an operator turns it on.</p>
 */
public final class BodyToolRegistry {

    private BodyToolRegistry() {
    }

    public static void register(boolean actionsEnabled, boolean placeEnabled) {
        ensure(BodyListTool.ID, BodyListTool::new);
        ensure(BodyMoveTool.ID, BodyMoveTool::new);
        ensure(BodyLookTool.ID, BodyLookTool::new);
        ensure(BodyMineTool.ID, BodyMineTool::new);
        ensure(BodyAttackTool.ID, BodyAttackTool::new);
        ensure(BlockPlaceTool.ID, BlockPlaceTool::new);
        ensure(BodyScanTool.ID, BodyScanTool::new);
        ensure(BodyInventoryTool.ID, BodyInventoryTool::new);
        TianshuTools.setEnabled(BodyMoveTool.ID, actionsEnabled);
        TianshuTools.setEnabled(BodyLookTool.ID, actionsEnabled);
        TianshuTools.setEnabled(BodyMineTool.ID, actionsEnabled);
        TianshuTools.setEnabled(BodyAttackTool.ID, actionsEnabled);
        TianshuTools.setEnabled(BlockPlaceTool.ID, placeEnabled);
    }

    private static void ensure(ToolId id, Supplier<TianshuTool<?, ?>> factory) {
        if (TianshuTools.find(id) == null) {
            TianshuTools.register(factory.get());
        }
    }
}