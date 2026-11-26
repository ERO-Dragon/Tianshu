package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dimension parameter exists so a confused caller is told it is confused — never so a body can travel.
 *
 * <p>The check has to live in the tool layer: the body's coordinates are in range whatever dimension the caller meant,
 * so nothing downstream can tell that the request was aimed at another world.</p>
 */
class BodyDimensionRefusalTest {

    private static final String BODY = "lab:dimension-body";

    private Runnable unregister;

    @BeforeEach
    void registerBody() {
        unregister = TestBodies.register(BODY, EntityCapability.MOVE, EntityCapability.LOOK);
        TianshuTools.registerBuiltin(new BodyMoveTool());
        TianshuTools.setEnabled(BodyMoveTool.ID, true);
    }

    @AfterEach
    void clean() {
        TianshuTools.clearEnabledOverride(BodyMoveTool.ID);
        TianshuTools.unregister(BodyMoveTool.ID);
        unregister.run();
    }

    @Test
    void aRequestThatNamesAnotherDimensionIsRefusedWithBothDimensions() {
        JsonObject args = new JsonObject();
        args.addProperty("body", BODY);
        args.addProperty("x", 0);
        args.addProperty("y", 64);
        args.addProperty("z", 0);
        args.addProperty("dimension", "minecraft:the_nether");

        String message = refusalMessage(args);

        assertTrue(message.contains("minecraft:overworld"), "the refusal must say where the body actually is: " + message);
        assertTrue(message.contains("minecraft:the_nether"), "and where the caller thought it was: " + message);
    }

    @Test
    void namingTheBodysOwnDimensionIsNotARefusal() {
        JsonObject args = new JsonObject();
        args.addProperty("body", BODY);
        args.addProperty("x", 0);
        args.addProperty("y", 64);
        args.addProperty("z", 0);
        args.addProperty("dimension", "minecraft:overworld");

        // No motion port is installed here, so the call stops at UNAVAILABLE — past the dimension check, which is the
        // claim: a correct dimension must not be rejected.
        String code = callCode(args);

        assertFalse("INVALID_ARGS".equals(code), "a matching dimension must not be refused: " + code);
    }

    @Test
    void theDimensionIsOptionalOnTheWire() {
        JsonObject schema = new BodyMoveTool().argsSchema();
        assertTrue(schema.getAsJsonObject("properties").has("dimension"), "the parameter must be offered");
        for (var required : schema.getAsJsonArray("required")) {
            assertFalse("dimension".equals(required.getAsString()),
                    "it is optional: the caller runtime fills in the body, not the destination world");
        }
    }

    private String refusalMessage(JsonObject args) {
        try {
            JsonObject envelope = TianshuTools.callJson(BodyMoveTool.ID, args, ToolCaller.agent(true), ToolSide.SERVER)
                    .get(2, TimeUnit.SECONDS);
            // The refusal may arrive as a flat envelope; the claim is about the text, not the key it sits under.
            return envelope.toString();
        } catch (ExecutionException failure) {
            return String.valueOf(failure.getCause().getMessage());
        } catch (Exception other) {
            return other.getClass().getSimpleName();
        }
    }

    private String callCode(JsonObject args) {
        try {
            JsonObject envelope = TianshuTools.callJson(BodyMoveTool.ID, args, ToolCaller.agent(true), ToolSide.SERVER)
                    .get(2, TimeUnit.SECONDS);
            return envelope.has("code") ? envelope.get("code").getAsString() : "OK";
        } catch (ExecutionException failure) {
            return failure.getCause().getClass().getSimpleName() + ": " + failure.getCause().getMessage();
        } catch (Exception other) {
            return other.getClass().getSimpleName();
        }
    }
}