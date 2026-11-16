package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntitySlot;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.EquipResult;
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

/** Moves an inventory slot onto the body's main hand, off hand or an armour slot; -1 clears the target. */
public final class BodyEquipTool implements TianshuTool<BodyEquipTool.Args, EquipResult> {

    public static final ToolId ID = ToolId.of("tianshu", "body.equip");

    private static final int MAIN_SLOTS = 36;

    /** {@code slot=-1} clears {@code target}; {@code target} defaults to the main hand. */
    public record Args(String body, int slot, String target) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_WRITE;
    }

    @Override
    public String description() {
        return "Equips an inventory slot into the body's main hand, off hand, head, chest, legs or feet; -1 clears.";
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
        properties.add("slot", BodyToolSchemas.integer("Main inventory slot 0..35, or -1 to clear the target"));
        properties.add("target", BodyToolSchemas.text(EntitySlot.wireNameList()
                + "; defaults to " + EntitySlot.MAIN_HAND.wireName()));
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
        // The schema advertises EntitySlot.wireNameList(); parsing reads the same source, so the two cannot drift.
        EntitySlot target = args.target() == null || args.target().isBlank()
                ? EntitySlot.MAIN_HAND
                : EntitySlot.fromWireName(args.target());
        if (target == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "target must be " + EntitySlot.wireNameList()));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        ActorRef actor = scope.actor();
        EquipRequest request = new EquipRequest(args.slot(), target);
        return TianshuRuntimePorts.find(EntityItemPort.class)
                .map(port -> port.equip(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "equipping is only available on the server")));
    }
}