package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.BlockFace;
import com.rheinmetal.tianshu.api.entity.BlockTarget;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntitySlot;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.ItemUseTarget;
import com.rheinmetal.tianshu.api.entity.MoveMode;
import com.rheinmetal.tianshu.api.entity.MoveOptions;
import com.rheinmetal.tianshu.api.entity.SequenceResult;
import com.rheinmetal.tianshu.api.entity.SequenceStep;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a bounded list of body steps in order: data, not a program.
 *
 * <p>Every step goes through the same provider handlers the individual tools use, under one body operation slot, and
 * the sequence stops at the first failure or when its budget runs out. It deliberately has no variables, conditions,
 * loops or nested sequences; planning and repair belong to the orchestration layer.</p>
 */
public final class BodySequenceTool implements TianshuTool<BodySequenceTool.Args, SequenceResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.sequence");

    private static final int MAX_STEPS = 32;
    private static final int BUDGET_SECONDS = 120;

    /** {@code steps} is the ordered list; each entry names a {@code kind} plus that step's fields. */
    public record Args(String body, JsonArray steps) {
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
        return "Runs up to 32 body steps in order (move/look/mine/place/interact/item_use/pickup/equip); "
                + "stops at the first failure.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return SequenceResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject step = new JsonObject();
        step.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray kinds = new JsonArray();
        for (String kind : List.of("move", "look", "mine", "place", "interact", "item_use", "pickup", "equip")) {
            kinds.add(kind);
        }
        JsonObject kind = BodyToolSchemas.text("Step kind");
        kind.add("enum", kinds);
        properties.add("kind", kind);
        properties.add("x", BodyToolSchemas.coordinate("Target x (move/look/mine/place/interact/item_use)"));
        properties.add("y", BodyToolSchemas.coordinate("Target y"));
        properties.add("z", BodyToolSchemas.coordinate("Target z"));
        properties.add("mode", BodyToolSchemas.text("Move mode for move steps: any, walk, fly, teleport"));
        properties.add("item", BodyToolSchemas.text("Item id for a place step"));
        properties.add("face", BodyToolSchemas.text("Block face for place/interact/item_use"));
        properties.add("holdTicks", BodyToolSchemas.integer("Held-use ticks for item_use; 0 means the item's own"));
        properties.add("radius", BodyToolSchemas.integer("Radius for a pickup step; defaults to 3"));
        properties.add("slot", BodyToolSchemas.integer("Inventory slot for an equip step; -1 clears"));
        properties.add("target", BodyToolSchemas.text("Body slot for equip: main, offhand, head, chest, legs, feet"));
        step.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("kind");
        step.add("required", required);

        JsonObject steps = new JsonObject();
        steps.addProperty("type", "array");
        steps.add("items", step);
        steps.addProperty("description", "Ordered steps; 1..32");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject root = new JsonObject();
        root.add("body", BodyToolSchemas.body());
        root.add("steps", steps);
        schema.add("properties", root);
        JsonArray rootRequired = new JsonArray();
        rootRequired.add("steps");
        schema.add("required", rootRequired);
        return schema;
    }

    @Override
    public ToolPolicy policy() {
        return ToolPolicy.forCategory(category()).withTimeout(125_000L);
    }

    @Override
    public CompletableFuture<SequenceResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (scope.actor().kind() != ActorRef.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.sequence needs an agent body"));
        }
        ActorRef actor = scope.actor();
        // A body that cannot be resolved has no dimension to plan against; the old code froze an empty string into
        // every step and let the adapter explain it as "the target is in another dimension".
        String dimension = dimensionOf(actor);
        if (dimension.isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "this body is no longer available"));
        }
        List<SequenceStep> steps;
        try {
            steps = parse(dimension, args == null ? null : args.steps());
        } catch (ToolException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUDGET_SECONDS);
        return ActorScope.guarded(actor, context, () -> run(actor, steps, 0, new ArrayList<>(), deadline, dimension))
                .thenApply(BodySequenceTool::demandCompletion);
    }

    /**
     * Turns a partial run into a failed call.
     *
     * <p>A stop was reported only inside the result, so an orchestration layer that looks at the envelope's top-level
     * {@code ok} read "step 0 was refused" as a success — the exact opposite of what happened. The step index and the
     * reason travel in the failure message; the full per-step report stays available to code that drives the body
     * layer directly, through {@link SequenceResult#completed()}.</p>
     */
    private static SequenceResult demandCompletion(SequenceResult result) {
        if (result.completed()) {
            return result;
        }
        throw new ToolException(ToolError.FAILED, "body.sequence stopped at step " + result.failedIndex()
                + ": " + result.detail());
    }

    private static String dimensionOf(ActorRef actor) {
        return EntityActors.resolve(actor.id()).map(body -> body.position().dimension()).orElse("");
    }

    private List<SequenceStep> parse(String dimension, JsonArray rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            throw new ToolException(ToolError.INVALID_ARGS, "body.sequence needs at least one step");
        }
        if (rawSteps.size() > MAX_STEPS) {
            throw new ToolException(ToolError.INVALID_ARGS, "too many steps: " + rawSteps.size() + " > " + MAX_STEPS);
        }
        List<SequenceStep> steps = new ArrayList<>();
        for (int index = 0; index < rawSteps.size(); index++) {
            JsonElement element = rawSteps.get(index);
            if (!element.isJsonObject()) {
                throw new ToolException(ToolError.INVALID_ARGS, "step " + index + " must be an object");
            }
            JsonObject step = element.getAsJsonObject();
            String kind = text(step, "kind").toLowerCase(Locale.ROOT);
            steps.add(switch (kind) {
                case "move" -> new SequenceStep.Move(position(dimension, step), mode(step));
                case "look" -> new SequenceStep.Look(position(dimension, step));
                case "mine" -> new SequenceStep.Mine(block(dimension, step));
                case "place" -> new SequenceStep.Place(block(dimension, step), text(step, "item"), face(step));
                case "interact" -> new SequenceStep.Interact(block(dimension, step));
                case "item_use" -> new SequenceStep.ItemUse(itemUse(dimension, step), integer(step, "holdTicks", 0));
                case "pickup" -> new SequenceStep.Pickup(integer(step, "radius", 3));
                case "equip" -> new SequenceStep.Equip(new EquipRequest(integer(step, "slot", -1),
                        EntitySlot(step)));
                default -> throw new ToolException(ToolError.INVALID_ARGS,
                        "step " + index + " has an unknown kind: " + kind);
            });
        }
        return steps;
    }

    private CompletableFuture<SequenceResult> run(ActorRef actor, List<SequenceStep> steps, int index,
                                                  List<SequenceResult.StepReport> reports, long deadline,
                                                  String dimension) {
        if (index >= steps.size()) {
            return CompletableFuture.completedFuture(new SequenceResult(List.copyOf(reports), -1,
                    "all " + steps.size() + " steps completed"));
        }
        if (BodyOperations.cancelled(actor.id())) {
            return CompletableFuture.completedFuture(stop(reports, index, "the call was cancelled"));
        }
        if (System.nanoTime() > deadline) {
            return CompletableFuture.completedFuture(stop(reports, index, "the sequence budget was exhausted"));
        }
        // Every step carries the dimension that was current when the sequence was parsed. If the body was moved to
        // another dimension in between (a portal, a command, another player), the remaining steps would describe a
        // coordinate in a world the body is no longer in — and the adapter would report it as a cross-dimension
        // attempt. Stop and say so instead of acting somewhere else.
        String current = dimensionOf(actor);
        if (!current.equals(dimension)) {
            return CompletableFuture.completedFuture(stop(reports, index, current.isBlank()
                    ? "the body disappeared after step " + (index - 1)
                    : "the body moved to " + current + " after step " + (index - 1)
                            + "; the remaining steps were planned in " + dimension));
        }
        SequenceStep current_step = steps.get(index);
        Optional<ToolException> refusal = capabilityRefusal(actor, current_step);
        if (refusal.isPresent()) {
            reports.add(new SequenceResult.StepReport(current_step.kind(), false, refusal.get().getMessage()));
            return CompletableFuture.completedFuture(stop(reports, index, refusal.get().getMessage()));
        }
        return dispatch(actor, current_step).handle((outcome, failure) -> {
            boolean ok = failure == null && outcome != null && outcome.ok();
            String detail = failure != null
                    ? (failure.getMessage() == null ? "the step failed" : failure.getMessage())
                    : (outcome == null ? "the step produced no answer" : outcome.detail());
            reports.add(new SequenceResult.StepReport(current_step.kind(), ok, detail));
            if (!ok) {
                return CompletableFuture.completedFuture(stop(reports, index, detail));
            }
            return run(actor, steps, index + 1, reports, deadline, dimension);
        }).thenCompose(next -> next);
    }

    private static SequenceResult stop(List<SequenceResult.StepReport> reports, int index, String detail) {
        return new SequenceResult(List.copyOf(reports), index, detail);
    }

    private static Optional<ToolException> capabilityRefusal(ActorRef actor, SequenceStep step) {
        Optional<ToolException> refusal = EntityActors.checkCapability(actor, capability(step));
        if (refusal.isPresent()) {
            return refusal;
        }
        if (step instanceof SequenceStep.ItemUse) {
            return EntityActors.checkCapability(actor, EntityCapability.INVENTORY);
        }
        return Optional.empty();
    }

    private static EntityCapability capability(SequenceStep step) {
        if (step instanceof SequenceStep.Move) {
            return EntityCapability.MOVE;
        }
        if (step instanceof SequenceStep.Look) {
            return EntityCapability.LOOK;
        }
        if (step instanceof SequenceStep.Mine) {
            return EntityCapability.MINE;
        }
        if (step instanceof SequenceStep.Place) {
            return EntityCapability.PLACE;
        }
        if (step instanceof SequenceStep.Interact) {
            return EntityCapability.USE;
        }
        if (step instanceof SequenceStep.ItemUse) {
            return EntityCapability.USE;
        }
        if (step instanceof SequenceStep.Pickup) {
            return EntityCapability.INVENTORY;
        }
        return EntityCapability.INVENTORY;
    }

    private CompletableFuture<StepOutcome> dispatch(ActorRef actor, SequenceStep step) {
        Optional<EntityActions> actions = EntityActors.actions(actor.id());
        if (actions.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "this body has no action handlers"));
        }
        EntityActions handlers = actions.get();
        if (step instanceof SequenceStep.Move move) {
            return handlers.motion()
                    .map(handler -> handler.move(actor, move.target(), new MoveOptions(move.mode(), 0.5D))
                            .thenApply(result -> outcome(result.outcome(), result.detail())))
                    .orElseGet(() -> missing("motion"));
        }
        if (step instanceof SequenceStep.Look look) {
            return handlers.motion()
                    .map(handler -> handler.look(actor, look.target())
                            .thenApply(result -> outcome(result.outcome(), result.detail())))
                    .orElseGet(() -> missing("motion"));
        }
        if (step instanceof SequenceStep.Mine mine) {
            return handlers.action()
                    .map(handler -> handler.mine(actor, mine.target())
                            .thenApply(result -> outcome(result.outcome(), result.detail())))
                    .orElseGet(() -> missing("action"));
        }
        if (step instanceof SequenceStep.Place place) {
            return handlers.placement()
                    .map(handler -> handler.place(actor, new BlockPlaceRequest(place.target().x(),
                                    place.target().y(), place.target().z(), place.itemId(), place.face()))
                            .thenApply(result -> new StepOutcome(result.placed(), result.message())))
                    .orElseGet(() -> missing("placement"));
        }
        if (step instanceof SequenceStep.Interact interact) {
            return handlers.interaction()
                    .map(handler -> handler.interact(actor, new BlockInteractRequest(interact.target().x(),
                                    interact.target().y(), interact.target().z()))
                            .thenApply(result -> new StepOutcome(result.interacted(), result.message())))
                    .orElseGet(() -> missing("interaction"));
        }
        if (step instanceof SequenceStep.ItemUse use) {
            return handlers.itemUse()
                    .map(handler -> handler.use(actor, use.target(), use.holdTicks())
                            .thenApply(result -> outcome(result.outcome(), result.detail())))
                    .orElseGet(() -> missing("item use"));
        }
        if (step instanceof SequenceStep.Pickup pickup) {
            return handlers.items()
                    .map(handler -> handler.pickup(actor, pickup.radius())
                            .thenApply(result -> new StepOutcome(true,
                                    "picked up " + result.items().size() + " stacks")))
                    .orElseGet(() -> missing("item"));
        }
        SequenceStep.Equip equip = (SequenceStep.Equip) step;
        return handlers.items()
                .map(handler -> handler.equip(actor, equip.request())
                        .thenApply(result -> new StepOutcome(true,
                                "equipped " + result.itemId() + " on " + result.target())))
                .orElseGet(() -> missing("item"));
    }

    private static StepOutcome outcome(ActionOutcome outcome, String detail) {
        return new StepOutcome(outcome == ActionOutcome.DONE, detail);
    }

    private static CompletableFuture<StepOutcome> missing(String handler) {
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "this body has no " + handler + " handler"));
    }

    private record StepOutcome(boolean ok, String detail) {
    }

    private static ActorPosition position(String dimension, JsonObject step) {
        return BodyTargets.position(dimension, number(step, "x"), number(step, "y"), number(step, "z"));
    }

    private static BlockTarget block(String dimension, JsonObject step) {
        return BodyTargets.block(dimension, integer(step, "x", Integer.MIN_VALUE),
                integer(step, "y", Integer.MIN_VALUE), integer(step, "z", Integer.MIN_VALUE));
    }

    private static ItemUseTarget itemUse(String dimension, JsonObject step) {
        int given = (step.has("x") ? 1 : 0) + (step.has("y") ? 1 : 0) + (step.has("z") ? 1 : 0);
        if (given == 0) {
            return ItemUseTarget.Air.INSTANCE;
        }
        if (given != 3) {
            // Same rule as item.use: all three coordinates or none. Half a target used to mean "on the air".
            throw new ToolException(ToolError.INVALID_ARGS,
                    "an item_use step needs all of x, y and z, or none of them");
        }
        return new ItemUseTarget.Block(block(dimension, step), face(step));
    }

    private static MoveMode mode(JsonObject step) {
        String raw = step.has("mode") ? text(step, "mode") : "any";
        try {
            return MoveMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ToolException(ToolError.INVALID_ARGS, "unknown move mode: " + raw);
        }
    }

    private static BlockFace face(JsonObject step) {
        if (!step.has("face")) {
            return null;
        }
        String raw = text(step, "face");
        try {
            return BlockFace.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ToolException(ToolError.INVALID_ARGS, "unknown face: " + raw);
        }
    }

    private static EntitySlot EntitySlot(JsonObject step) {
        String raw = step.has("target") ? text(step, "target") : EntitySlot.MAIN_HAND.wireName();
        EntitySlot slot = EntitySlot.fromWireName(raw);
        if (slot == null) {
            throw new ToolException(ToolError.INVALID_ARGS,
                    "unknown equip target: " + raw + "; expected " + EntitySlot.wireNameList());
        }
        return slot;
    }

    private static double number(JsonObject step, String key) {
        if (!step.has(key) || !step.get(key).isJsonPrimitive()
                || !step.get(key).getAsJsonPrimitive().isNumber()) {
            throw new ToolException(ToolError.INVALID_ARGS, "step needs a numeric " + key);
        }
        return step.get(key).getAsDouble();
    }

    private static int integer(JsonObject step, String key, int fallback) {
        if (!step.has(key)) {
            if (fallback == Integer.MIN_VALUE) {
                throw new ToolException(ToolError.INVALID_ARGS, "step needs an integer " + key);
            }
            return fallback;
        }
        try {
            return step.get(key).getAsInt();
        } catch (RuntimeException malformed) {
            throw new ToolException(ToolError.INVALID_ARGS, "step needs an integer " + key);
        }
    }

    private static String text(JsonObject step, String key) {
        if (!step.has(key) || !step.get(key).isJsonPrimitive()) {
            throw new ToolException(ToolError.INVALID_ARGS, "step needs a string " + key);
        }
        String value = step.get(key).getAsString();
        if (value == null || value.isBlank()) {
            throw new ToolException(ToolError.INVALID_ARGS, "step needs a non-empty " + key);
        }
        return value;
    }
}