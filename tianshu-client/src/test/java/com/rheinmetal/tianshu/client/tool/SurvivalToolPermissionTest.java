package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTool;
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
 */
class SurvivalToolPermissionTest {

    private static final List<TianshuTool<?, ?>> SURVIVAL_TOOLS = List.of(
            new CraftRecipeTool(),
            new ContainerTransferTool(),
            new BlockMachineTool(),
            new BlockInteractTool(),
            new BlockPlaceTool());

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
}