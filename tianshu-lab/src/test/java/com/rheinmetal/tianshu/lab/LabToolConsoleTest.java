package com.rheinmetal.tianshu.lab;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabToolConsoleTest {

    private static final ToolId TOOL = ToolId.of("labtest", "echo");

    @Test
    void aConsoleCallPublishesItsResultToDiagnostics() throws Exception {
        TianshuTools.register(new EchoTool());
        List<DiagnosticEvent> events = new ArrayList<>();
        LabToolConsole console = new LabToolConsole(events::add);
        console.setToolId(TOOL.toString());
        console.setArgsJson("{}");
        try {
            console.run();
            for (int attempt = 0; attempt < 100 && console.running(); attempt++) {
                Thread.sleep(10);
            }
            assertFalse(console.running());
            assertTrue(console.result().contains("\"ok\":true"));
            assertEquals(1, events.size());
            assertEquals("tool.console", events.get(0).code());
            assertEquals(TOOL.toString(), events.get(0).attributes().get("tool"));
            assertEquals("true", events.get(0).attributes().get("ok"));
        } finally {
            TianshuTools.unregister(TOOL);
        }
    }

    private record EchoTool() implements TianshuTool<JsonObject, JsonObject> {
        @Override
        public ToolId id() {
            return TOOL;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "lab console test";
        }

        @Override
        public Type argsType() {
            return JsonObject.class;
        }

        @Override
        public Type resultType() {
            return JsonObject.class;
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }
}
