package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.EquipResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Chooses the body's active main slot; attacks and breaks then use that item. */
public final class BodyEquipTool implements TianshuTool<BodyEquipTool.Args, EquipResult> {

    public static final ToolId ID = ToolId.of("tianshu", "body.equip");

    private static final int MAIN_SLOTS = 36;

    /** {@code slot=-1} clears the selection. */
    public record Args(String body, int slot) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
    }

    @Override
    public String description() {
        return "Chooses which inventory slot the body uses for attacks and breaks; -1 clears it.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return EquipResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("slot", BodyToolSchemas.integer("Main inventory slot 0..35, or -1 to clear"));
        return BodyToolSchemas.object(properties, "slot");
    }

    @Override
    public CompletableFuture<EquipResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (scope.actor().kind() != ActorRef.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.equip needs an agent body"));
        }
        if (args == null || args.slot() < -1 || args.slot() >= MAIN_SLOTS) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "slot must be -1 or 0..35"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(BodyCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        ActorRef actor = scope.actor();
        int slot = args.slot();
        return TianshuRuntimePorts.find(AgentItemPort.class)
                .map(port -> port.equip(actor, slot))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "equipping is only available on the server")));
    }
}