package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.host.game.GameContainerEntry;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What is inside the container the player currently has open.
 *
 * <p>Without this a model asked to put something in a chest has to guess the slot layout: {@code container.transfer}
 * moves by slot index, and nothing told it which index holds what. The read is the client's own open menu — no request
 * to the server, no other player's inventory.</p>
 */
public final class ContainerInspectTool implements TianshuTool<EmptyArgs, ContainerInspectTool.View> {

    public static final ToolId ID = ToolId.of("tianshu", "container.inspect");

    /**
     * The open container, or {@code open=false} when the player has none.
     *
     * <p>"Nothing is open" is an answer, not an error: a model that asked while no chest was open should be told that,
     * not handed a failure it cannot tell apart from a broken platform.</p>
     */
    public record View(boolean open, String title, List<GameContainerEntry> entries) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.LOCAL;
    }

    @Override
    public String description() {
        return "Lists the contents of the container the player has open, with the slot index each stack sits in "
                + "(the same indexes container.transfer takes). Returns open=false when no container is open.";
    }

    @Override
    public Type argsType() {
        return EmptyArgs.class;
    }

    @Override
    public Type resultType() {
        return View.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public CompletableFuture<View> invoke(ToolContext context, EmptyArgs args) {
        ClientGameContextPort port = TianshuRuntimePorts.find(ClientGameContextPort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNSUPPORTED_SIDE,
                    "this platform cannot read the open container"));
        }
        var view = port.captureOpenContainer();
        boolean open = !view.title().isBlank() || !view.entries().isEmpty();
        return CompletableFuture.completedFuture(new View(open, view.title(), view.entries()));
    }
}