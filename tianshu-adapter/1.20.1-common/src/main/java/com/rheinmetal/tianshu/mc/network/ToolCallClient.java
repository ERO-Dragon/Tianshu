package com.rheinmetal.tianshu.mc.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolTransport;
import com.rheinmetal.tianshu.client.tool.ConnectionToolChannel;
import com.rheinmetal.tianshu.client.tool.ServerToolDeclarations;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** The client half of the tool channel on the legacy channel; senders are supplied by the adapter. */
public final class ToolCallClient implements ToolTransport {

    @FunctionalInterface
    public interface Sender {
        void send(ToolCallRequestPacket packet);
    }

    private final Sender sender;
    private final AtomicBoolean catalogReceived = new AtomicBoolean();

    public ToolCallClient(Sender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public boolean available() {
        return catalogReceived.get();
    }

    @Override
    public void send(String requestId, ToolId id, JsonElement args, ToolCaller caller, long timeoutMillis) {
        sender.send(new ToolCallRequestPacket(requestId, id.toString(), args == null ? "{}" : args.toString()));
    }

    public void receiveCatalog(ToolCatalogPacket packet) {
        ServerToolDeclarations.activate(packet.toolIds());
        catalogReceived.set(true);
    }

    public void receiveResult(ToolCallResultPacket packet) {
        if (packet.ok()) {
            JsonElement value;
            try {
                value = JsonParser.parseString(packet.valueJson());
            } catch (RuntimeException malformed) {
                ConnectionToolChannel.fail(packet.requestId(), ToolError.FAILED, "the server sent a malformed result");
                return;
            }
            ConnectionToolChannel.complete(packet.requestId(), value);
            return;
        }
        ConnectionToolChannel.fail(packet.requestId(), codeOf(packet.errorCode()), packet.message());
    }

    public void onDisconnect() {
        catalogEnabled(false);
    }

    private void catalogEnabled(boolean enabled) {
        catalogReceived.set(enabled);
        if (!enabled) {
            ServerToolDeclarations.deactivateAll();
        }
    }

    private static ToolError codeOf(String code) {
        try {
            return ToolError.valueOf(code);
        } catch (RuntimeException unknown) {
            return ToolError.FAILED;
        }
    }
}
