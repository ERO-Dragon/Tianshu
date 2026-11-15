package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.ReservedToolCalls;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A timed-out call must ask the server to stop it, carrying the same request id and caller. */
class ChannelTimeoutCancelTest {

    private static final ToolId SLOW = ToolId.of("tianshu", "test.slow");

    private final List<String> cancelled = new CopyOnWriteArrayList<>();
    private final List<ToolCaller> cancelCallers = new CopyOnWriteArrayList<>();

    @AfterEach
    void clean() {
        TianshuTools.unregister(SLOW);
        ConnectionToolChannel.uninstall();
        TianshuRuntimePorts.clear();
    }

    @Test
    void timeoutSendsTheReservedCancelCall() {
        TianshuTools.register(new TianshuTool<JsonObject, JsonObject>() {
            @Override
            public ToolId id() {
                return SLOW;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.SERVER_WRITE;
            }

            @Override
            public String description() {
                return "never answers";
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
            public JsonObject argsSchema() {
                return new JsonObject();
            }

            @Override
            public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
                return new CompletableFuture<>();
            }

            @Override
            public ToolPolicy policy() {
                return ToolPolicy.forCategory(ToolCategory.SERVER_WRITE).withTimeout(100L);
            }
        });
        TianshuTools.setEnabled(SLOW, true);
        TianshuRuntimePorts.register(ToolTransport.class, new ToolTransport() {
            @Override
            public void send(String requestId, ToolId id, JsonElement args, ToolCaller caller, long timeoutMillis) {
                if (ReservedToolCalls.CANCEL.equals(id) && args != null && args.isJsonObject()) {
                    cancelled.add(args.getAsJsonObject().get("requestId").getAsString());
                    cancelCallers.add(caller);
                }
            }
        });
        ConnectionToolChannel.install();
        ToolCaller caller = ToolCaller.player("player-1", true);

        JsonObject envelope = TianshuTools.callJson(SLOW, new JsonObject(), caller, ToolSide.CLIENT).join();
        assertFalse(envelope.get("ok").getAsBoolean());

        for (int waited = 0; waited < 100 && cancelled.isEmpty(); waited++) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(1, cancelled.size(), envelope.toString());
        assertEquals(caller, cancelCallers.get(0));
        assertTrue(cancelled.get(0).startsWith("tool-"));
    }
}