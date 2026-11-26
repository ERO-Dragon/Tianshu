package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line of blocks is placed step by step, and the answer says how far it got.
 *
 * <p>Failing the whole call because the last block did not land would throw away what the model needs most: how much
 * of the line exists now.</p>
 */
class BlockPlaceLineToolTest {

    private static final String BODY = "lab:builder";
    private static final int LIMIT = 2;

    private final List<String> placed = new ArrayList<>();
    private Runnable unregister;

    @BeforeEach
    void setUp() {
        unregister = TestBodies.register(BODY, com.rheinmetal.tianshu.api.entity.EntityCapability.PLACE);
        TianshuTools.register(new BlockPlaceLineTool());
        TianshuTools.register(new BodyPlaceLineTool());
        TianshuTools.setEnabled(BlockPlaceLineTool.ID, true);
        TianshuTools.setEnabled(BodyPlaceLineTool.ID, true);
    }

    @AfterEach
    void clean() {
        TianshuTools.clearEnabledOverride(BlockPlaceLineTool.ID);
        TianshuTools.unregister(BlockPlaceLineTool.ID);
        TianshuTools.unregister(BodyPlaceLineTool.ID);
        unregister.run();
        TianshuRuntimePorts.clear();
    }

    @Test
    void aLineStopsWhereTheWorldStopsItAndSaysSo() throws Exception {
        install(true);

        PlaceLineOps.Placed result = call(5, "east");

        assertEquals(LIMIT, result.placed().size(), "only the blocks that landed may be reported");
        assertEquals(5, result.requested());
        assertTrue(!result.stoppedBecause().isBlank(), "the answer must say why the line stopped short");
        assertEquals(List.of("0,64,0", "1,64,0"), placed);
    }

    @Test
    void aCompleteLinePlacesEveryStep() throws Exception {
        install(false);

        PlaceLineOps.Placed result = call(3, "up");

        assertEquals(3, result.placed().size());
        assertEquals("", result.stoppedBecause());
        assertEquals(List.of("0,64,0", "0,65,0", "0,66,0"), placed);
    }

    private void install(boolean refusesAfterLimit) {
        TianshuRuntimePorts.register(BlockPlacePort.class, (ActorRef actor, BlockPlaceRequest request) -> {
            if (refusesAfterLimit && placed.size() >= LIMIT) {
                return CompletableFuture.completedFuture(
                        new BlockPlaceResult(request.itemId(), request.x(), request.y(), request.z(), false,
                                "nothing left to place"));
            }
            placed.add(request.x() + "," + request.y() + "," + request.z());
            return CompletableFuture.completedFuture(
                    new BlockPlaceResult(request.itemId(), request.x(), request.y(), request.z(), true, "ok"));
        });
    }

    private PlaceLineOps.Placed call(int count, String direction) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("body", BODY);
        args.addProperty("item", "minecraft:stone");
        args.addProperty("x", 0);
        args.addProperty("y", 64);
        args.addProperty("z", 0);
        args.addProperty("direction", direction);
        args.addProperty("count", count);
        return TianshuTools
                .callJson(BodyPlaceLineTool.ID, args, ToolCaller.agent(true), ToolSide.SERVER)
                .thenApply(envelope -> {
                    var value = envelope.getAsJsonObject("result");
                    var blocks = new ArrayList<BlockPlaceResult>();
                    for (var element : value.getAsJsonArray("placed")) {
                        var block = element.getAsJsonObject();
                        blocks.add(new BlockPlaceResult(block.get("blockId").getAsString(),
                                block.get("x").getAsInt(), block.get("y").getAsInt(), block.get("z").getAsInt(),
                                block.get("placed").getAsBoolean(), block.get("message").getAsString()));
                    }
                    return new PlaceLineOps.Placed(blocks, value.get("requested").getAsInt(),
                            value.get("stoppedBecause").getAsString());
                })
                .get(2, TimeUnit.SECONDS);
    }
}