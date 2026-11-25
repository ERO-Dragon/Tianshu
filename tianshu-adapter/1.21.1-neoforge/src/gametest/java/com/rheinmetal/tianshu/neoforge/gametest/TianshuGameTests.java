package com.rheinmetal.tianshu.neoforge.gametest;

import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolId;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Game-side tests: the only place a real server world is available.
 *
 * <p>Everything in {@code tianshu-lab} runs against ports and payloads, deliberately without touching Minecraft, so it
 * cannot answer "did the item actually move". These tests live with the adapter, use the flat platform the harness
 * places, and assert on world state.</p>
 *
 * <p>Discovery is by annotation: the game scans a mod's classes for {@code @GameTestHolder} when the namespace is in
 * {@code neoforge.enabledGameTestNamespaces}, and {@code runGameTestServer} runs them headless. The run has its own game
 * directory — a developer's world, configs or leftover jars must not decide what a test sees.</p>
 */
@GameTestHolder(TianshuGameTests.MOD_ID)
// The holder already names the namespace; the default prefixing would qualify the template with the class name and
// produce an id that is not a resource location.
@PrefixGameTestTemplate(false)
public final class TianshuGameTests {
    static final String MOD_ID = "tianshu_ferry";
    /** A 3x3 stone platform: {@code data/tianshu_ferry/structure/plaza.nbt}, the fixture every test stands on. */
    private static final String PLAZA = "plaza";
    /** The one tool whose registration is a fact about the server session, not about any module being installed. */
    private static final ToolId SERVER_TOOL = ToolId.parse("minecraft:server.xp");

    /**
     * The harness can run at all: a server world exists, the session installed its tool channel, and a server tool is
     * there.
     *
     * <p>This is the smoke test on purpose. If it fails, every other test here says nothing — a suite that never ran
     * and a suite that passed look the same from the outside.</p>
     *
     * <p>The server registers its tools during startup, so the check waits instead of assuming a tick — the same reason
     * the client bench waits for the tool registry: a run against an empty table compares two empty views and calls it a
     * pass. Nothing here looks at {@code TianshuTools.serverAvailable()}: that bridge is the client's path to a server,
     * and a headless server has no client.</p>
     */
    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void theServerSessionIsUpAndToolsAreRegistered(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            if (helper.getLevel().getServer() == null) {
                throw new AssertionError("no server on this level");
            }

            if (TianshuTools.find(SERVER_TOOL) == null) {
                throw new AssertionError(SERVER_TOOL + " is missing: the server action tools were not registered");
            }
        });
    }
}