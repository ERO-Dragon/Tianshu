package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A call belongs to the connection it was sent on.
 *
 * <p>Leaving a server used to leave its calls pending: they held their per-caller slots until their own timeouts, so the
 * first calls on the next server — or on the player's own world — were answered {@code BUSY} because of work the
 * previous server was still doing.</p>
 */
class ToolChannelDisconnectTest {

    private static final ToolId REMOTE = ToolId.of("test", "remote.echo");

    @AfterEach
    void clean() {
        ConnectionToolChannel.uninstall();
        TianshuRuntimePortsHolder.clear();
        ServerToolDeclarations.clearForTests();
        TianshuTools.unregister(REMOTE);
    }

    @Test
    void leavingTheServerEndsItsPendingCallsAndFreesTheirSlots() throws Exception {
        ServerToolDeclarations.declareBuiltin(new RemoteTool());
        ToolCallClient client = new ToolCallClient((requestId, toolId, argsJson, callerModule) -> { });
        TianshuRuntimePortsHolder.install(client, new SilentToolTransport());
        // The server names the tool, which is what makes the channel reachable in the first place.
        client.receiveCatalog(java.util.List.of(
                new com.rheinmetal.tianshu.api.tool.ToolCatalogEntry(REMOTE.toString(), true)));

        // A call is in flight when the player leaves: it must end with the connection, not fifteen seconds later.
        CompletableFuture<com.google.gson.JsonObject> abandoned = TianshuTools.callJson(REMOTE,
                new JsonObject(), ToolCaller.agent(true));
        client.onDisconnect();

        // callJson reports failures as envelopes, so the code is what tells "ended with the connection" from a timeout.
        JsonObject envelope = abandoned.get(1, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", envelope.get("code").getAsString(),
                "a call on a closed connection must be ended, not left to time out");
    }

    /** A declared server tool: the client knows it, only the server can run it. */
    private static final class RemoteTool implements com.rheinmetal.tianshu.api.tool.TianshuTool<JsonObject, JsonObject> {
        @Override
        public ToolId id() {
            return REMOTE;
        }

        @Override
        public com.rheinmetal.tianshu.api.tool.ToolCategory category() {
            return com.rheinmetal.tianshu.api.tool.ToolCategory.SERVER_READ;
        }

        @Override
        public String description() {
            return "Runs on the server; a channel test double.";
        }

        @Override
        public java.lang.reflect.Type argsType() {
            return JsonObject.class;
        }

        @Override
        public java.lang.reflect.Type resultType() {
            return JsonObject.class;
        }

        @Override
        public JsonObject argsSchema() {
            return new JsonObject();
        }

        @Override
        public CompletableFuture<JsonObject> invoke(com.rheinmetal.tianshu.api.tool.ToolContext context,
                                                    JsonObject args) {
            return CompletableFuture.failedFuture(new IllegalStateException("the server runs this, not the client"));
        }
    }

    /** A transport that accepts calls and never answers, like a server that has gone away. */
    private static final class SilentToolTransport implements com.rheinmetal.tianshu.api.tool.ToolTransport {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void send(String requestId, ToolId id, JsonElement args, ToolCaller caller, long timeoutMillis) {
        }
    }

    /** The channel is installed through the runtime port, so the test wires the transport the same way. */
    private static final class TianshuRuntimePortsHolder {
        static void install(ToolCallClient client, com.rheinmetal.tianshu.api.tool.ToolTransport transport) {
            ConnectionToolChannel.install();
            com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts.register(
                    com.rheinmetal.tianshu.api.tool.ToolTransport.class, transport);
        }

        static void clear() {
            com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts.clear();
        }
    }
}