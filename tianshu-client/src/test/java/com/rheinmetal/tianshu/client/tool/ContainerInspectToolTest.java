package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.host.game.GameContainerEntry;
import com.rheinmetal.tianshu.client.host.game.GameContainerView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the open container is what makes {@code container.transfer}'s slot indexes usable.
 *
 * <p>The tool answers "nothing is open" as a value: a model that asked while no chest was open must be able to tell
 * that apart from a broken platform.</p>
 */
class ContainerInspectToolTest {

    private static final String SLOT_ITEM = "minecraft:iron_ingot";

    @BeforeEach
    void registerTool() {
        TianshuTools.register(new ContainerInspectTool());
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(ContainerInspectTool.ID);
        TianshuRuntimePorts.clear();
    }

    @Test
    void theOpenContainerComesBackWithTheSlotIndexesTransferTakes() throws Exception {
        install(new GameContainerView("Chest",
                List.of(new GameContainerEntry(3, SLOT_ITEM, "Iron Ingot", 12))));

        ContainerInspectTool.View result = call();

        assertTrue(result.open());
        assertEquals("Chest", result.title());
        var entry = result.entries().get(0);
        assertEquals(3, entry.slot(), "the slot index must be the one container.transfer takes");
        assertEquals(SLOT_ITEM, entry.itemId());
        assertEquals(12, entry.count());
    }

    @Test
    void nothingOpenIsAnAnswerRatherThanAFailure() throws Exception {
        install(GameContainerView.EMPTY);

        ContainerInspectTool.View result = call();

        assertFalse(result.open(), "no container open is a value, not an error");
        assertEquals(0, result.entries().size());
    }

    private void install(GameContainerView view) {
        TianshuRuntimePorts.register(ClientGameContextPort.class, new ClientGameContextPort() {
            @Override
            public String playerId() {
                return "";
            }

            @Override
            public com.rheinmetal.tianshu.client.host.game.GameInteractionContext captureInteraction() {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.rheinmetal.tianshu.client.host.game.GamePlayerStatus capturePlayerStatus() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<com.rheinmetal.tianshu.client.host.game.GameInventoryEntry> captureInventory() {
                return List.of();
            }

            @Override
            public com.rheinmetal.tianshu.client.host.game.GameEquipment captureEquipment() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<com.rheinmetal.tianshu.client.host.game.GameEffect> captureEffects() {
                return List.of();
            }

            @Override
            public com.rheinmetal.tianshu.client.host.game.GameWorldEnvironment captureEnvironment() {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.rheinmetal.tianshu.client.host.game.GameBlockTarget captureBlockTarget() {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.rheinmetal.tianshu.client.host.game.GamePlayerPose capturePlayerPose() {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.rheinmetal.tianshu.client.host.game.GamePlayerVitals captureVitals() {
                throw new UnsupportedOperationException();
            }

            @Override
            public GameContainerView captureOpenContainer() {
                return view;
            }

            @Override
            public List<com.rheinmetal.tianshu.client.host.game.GamePlayerListEntry> capturePlayerList() {
                return List.of();
            }
        });
    }

    private ContainerInspectTool.View call() throws Exception {
        return TianshuTools.handle(ContainerInspectTool.ID, EmptyArgs.class, ContainerInspectTool.View.class)
                .call(new EmptyArgs(), ToolCaller.agent(true), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
    }
}