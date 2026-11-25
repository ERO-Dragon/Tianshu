package com.rheinmetal.tianshu.client.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.client.tool.BodyMineTool;
import com.rheinmetal.tianshu.client.tool.CapabilityReadTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One tool, one document: the path is derived from the id, so nothing has to be kept in sync.
 *
 * <p>This replaced a coverage index that mapped skills to capability lists. The index could disagree with reality (a
 * typo documented nothing, two skills could claim one tool, and a document about thirteen tools was handed whole to
 * someone who had two). Deriving the path from the id removes all three at once.</p>
 */
class ToolDocsTest {

    private final CapabilityReadTool readTool = new CapabilityReadTool();

    @AfterEach
    void clean() {
        TianshuTools.unregister(BodyMineTool.ID);
        TianshuTools.unregister(CapabilityReadTool.ID);
    }

    @Test
    void theDocumentIsFoundFromTheToolId() {
        assertTrue(ToolDocs.of(CapabilityId.parse("tianshu:body.mine")).isPresent(),
                "tianshu:body.mine reads tianshu.body.mine.md");
        assertEquals("tianshu/skills/<skill>/references/tianshu.body.mine.md",
                ToolDocs.pathOf(CapabilityId.parse("tianshu:body.mine")));
    }

    @Test
    void aToolWithoutADocumentHasNone() {
        assertTrue(ToolDocs.of(CapabilityId.parse("tianshu:no.such.tool")).isEmpty());
    }

    @Test
    void readingAToolReturnsItsOwnDocument() throws Exception {
        TianshuTools.register(new BodyMineTool());
        TianshuTools.register(readTool);

        JsonObject answer = com.rheinmetal.tianshu.api.capability.CapabilityCatalog.invoke(CapabilityId.parse(CapabilityReadTool.ID.toString()),
                        request("tianshu:body.mine"), ToolCaller.agent(true), ToolSide.CLIENT)
                .thenCompose(envelope -> {
                    assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            envelope.getAsJsonObject("result"));
                }).get(5, TimeUnit.SECONDS);

        assertTrue(answer.get("text").getAsString().contains("body.mine"), answer.toString());
    }

    private static JsonObject request(String id) {
        JsonObject args = new JsonObject();
        args.addProperty("id", id);
        return args;
    }
}