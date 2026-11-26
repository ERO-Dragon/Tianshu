package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actions a survival player can do by hand are ordinary-player calls, not operator ones.
 *
 * <p>Each of these tools overrides its category's default permission, which is exactly the kind of thing that drifts:
 * a new survival tool gets written next year, inherits {@code SERVER_WRITE}'s operator requirement, and nobody notices
 * until a player is refused. This test is the reminder — it fails the moment one of them stops saying EVERYONE.</p>
 *
 * <p>The second thing it locks: these tools must not need a switch. Their gate is the autonomy ceiling, so a server on
 * {@code conservative} has to be able to run them without anyone flipping anything on first. A config override that
 * defaults to off would answer {@code DISABLED} to a caller the ceiling already allowed, which is the confusing state
 * this test exists to prevent.</p>
 */
class SurvivalToolPermissionTest {

    private static final List<TianshuTool<?, ?>> SURVIVAL_TOOLS = List.of(
            new CraftRecipeTool(),
            new ContainerTransferTool(),
            new BlockMachineTool(),
            new BlockInteractTool());

    /** What the player could do by hand: callable by them, and offered without anyone configuring it. */
    @Test
    void everySurvivalActionIsAnOrdinaryPlayerActionThatIsOnByDefault() {
        for (TianshuTool<?, ?> tool : SURVIVAL_TOOLS) {
            ToolPolicy policy = tool.policy();

            assertEquals(ToolCategory.SERVER_WRITE, tool.category(),
                    tool.id() + " is still a server-side world change");
            assertEquals(ToolPermission.EVERYONE, policy.permission(),
                    tool.id() + " is something a survival player does by hand, so it must not require an operator");
            assertTrue(policy.enabledByDefault(),
                    tool.id() + " is work a player does by hand, so it must not need configuring first");
        }
    }

    /** Registered and callable with nothing switched on: the ceiling is the only gate these have. */
    @Test
    void aSurvivalActionNeedsNoSwitch() {
        for (TianshuTool<?, ?> tool : SURVIVAL_TOOLS) {
            if (TianshuTools.find(tool.id()) != null) {
                TianshuTools.unregister(tool.id());
            }
            TianshuTools.register(tool);
            try {
                assertTrue(TianshuTools.isEnabled(tool.id()),
                        tool.id() + " must run once the ceiling allows it, with no switch to find first");
            } finally {
                TianshuTools.unregister(tool.id());
            }
        }
    }
}