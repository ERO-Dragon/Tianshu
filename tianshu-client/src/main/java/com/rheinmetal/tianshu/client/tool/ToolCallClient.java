package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolTransport;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The client half of the tool channel. It is transport-agnostic: the loader supplies a {@link RequestSender}, and
 * decoded packets arrive as plain fields. Every generation used to carry its own copy of this state machine; it
 * lives here once now.
 *
 * <p>The catalog packet is what marks the server component as reachable; until it arrives {@link #available()} is
 * false, so server tools stay invisible instead of timing out against a server that does not speak this channel.</p>
 */
public final class ToolCallClient implements ToolTransport {

    /** Sends one encoded call through the loader's channel. */
    @FunctionalInterface
    public interface RequestSender {
        void send(String requestId, String toolId, String argsJson);
    }

    private final RequestSender sender;
    private final AtomicBoolean catalogReceived = new AtomicBoolean();

    public ToolCallClient(RequestSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public boolean available() {
        return catalogReceived.get();
    }

    @Override
    public void send(String requestId, ToolId id, JsonElement args, ToolCaller caller, long timeoutMillis) {
        sender.send(requestId, id.toString(), args == null ? "{}" : args.toString());
    }

    public void receiveCatalog(List<String> toolIds) {
        ServerToolDeclarations.synchronize(toolIds);
        catalogReceived.set(true);
    }

    public void receiveResult(String requestId, boolean ok, String valueJson, String errorCode, String message) {
        if (ok) {
            JsonElement value;
            try {
                value = JsonParser.parseString(valueJson);
            } catch (RuntimeException malformed) {
                ConnectionToolChannel.fail(requestId, ToolError.FAILED, "the server sent a malformed result");
                return;
            }
            ConnectionToolChannel.complete(requestId, value);
            return;
        }
        ConnectionToolChannel.fail(requestId, codeOf(errorCode), message);
    }

    /** The client left the server; server tools become unreachable and their declarations are withdrawn. */
    public void onDisconnect() {
        catalogReceived.set(false);
        ServerToolDeclarations.deactivateAll();
    }

    private static ToolError codeOf(String code) {
        try {
            return ToolError.valueOf(code);
        } catch (RuntimeException unknown) {
            return ToolError.FAILED;
        }
    }
}
