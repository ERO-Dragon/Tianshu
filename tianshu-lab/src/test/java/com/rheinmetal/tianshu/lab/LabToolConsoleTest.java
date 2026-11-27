package com.rheinmetal.tianshu.lab;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.lab.tool.LabTools;
import com.rheinmetal.tianshu.api.entity.BodyFailure;
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

    @Test
    void consoleForwardsTheSelectedBenchIdentityAndLogsTheCallerThatRan() throws Exception {
        TianshuTools.registerBuiltin(new EchoTool());
        try {
            for (var role : List.of(LabToolConsole.Caller.LAB, LabToolConsole.Caller.PEER)) {
                String expected = role == LabToolConsole.Caller.LAB ? LabModuleRegistration.MOD_ID : LabTools.PEER_MODULE;
                CapabilitySubscriptions.declare(expected, CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
                var events = new ArrayList<DiagnosticEvent>();
                var console = new LabToolConsole(events::add);
                console.setCaller(role);
                console.setToolId(TOOL.toString());
                console.run();
                for (int attempt = 0; attempt < 100 && console.running(); attempt++) {
                    Thread.sleep(10);
                }
                assertFalse(console.running());
                assertEquals(expected, com.google.gson.JsonParser.parseString(console.result())
                        .getAsJsonObject().getAsJsonObject("result").get("caller").getAsString());
                assertEquals(ToolCaller.Kind.AGENT.name(), events.get(0).attributes().get("callerKind"));
                assertEquals(expected, events.get(0).attributes().get("callerId"));
            }
        } finally {
            CapabilitySubscriptions.withdraw(LabModuleRegistration.MOD_ID);
            CapabilitySubscriptions.withdraw(LabTools.PEER_MODULE);
            TianshuTools.unregister(TOOL);
        }
    }

    @Test
    void bodyRefusalKeepsItsWireIdAndTranslatesThePlayerView() throws Exception {
        TianshuTools.register(new EchoTool());
        LabToolConsole console = new LabToolConsole(null);
        console.setToolId(TOOL.toString());
        console.setArgsJson("{\"deny\":true}");
        try {
            console.run();
            for (int attempt = 0; attempt < 100 && console.running(); attempt++) {
                Thread.sleep(10);
            }
            assertFalse(console.running());
            assertTrue(console.result().contains("NO_PERMISSION"));
            assertTrue(console.result().contains("body.controller.mismatch"));
            assertTrue(console.displayResult().translatable());
            assertEquals("tianshu.body.controller.mismatch", console.displayResult().value());
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
            if (args.has("deny")) {
                return CompletableFuture.failedFuture(BodyFailure.CONTROLLER_MISMATCH.exception());
            }
            JsonObject result = new JsonObject();
            result.addProperty("caller", context.caller().id());
            return CompletableFuture.completedFuture(result);
        }
    }
}
