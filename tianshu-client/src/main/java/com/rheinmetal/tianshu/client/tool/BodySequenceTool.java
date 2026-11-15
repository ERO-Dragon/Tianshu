package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.BlockFace;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.BodySlot;
import com.rheinmetal.tianshu.api.agent.EquipRequest;
import com.rheinmetal.tianshu.api.agent.ItemUseTarget;
import com.rheinmetal.tianshu.api.agent.MoveMode;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.SequenceResult;
import com.rheinmetal.tianshu.api.agent.SequenceStep;
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

    public static final ToolId ID = ToolId.of("tianshu", "body.sequence");

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
        return ToolCategory.SERVER_WRITE;
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
        properties.add("x", BodyToolSchemas.integer("Target x (move/look/mine/place/interact/item_use)"));
        properties.add("y", BodyToolSchemas.integer("Target y"));
        properties.add("z", BodyToolSchemas.integer("Target z"));
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
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (scope.actor().kind() != ActorRef.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.sequence needs an agent body"));
        }
        List<SequenceStep> steps;
        try {
            steps = parse(scope.actor(), args == null ? null : args.steps());
        } catch (ToolException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUDGET_SECONDS);
        ActorRef actor = scope.actor();
        return ActorScope.guarded(actor, context, () -> run(actor, steps, 0, new ArrayList<>(), deadline));
    }

    private List<SequenceStep> parse(ActorRef actor, JsonArray rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            throw new ToolException(ToolError.INVALID_ARGS, "body.sequence needs at least one step");
        }
        if (rawSteps.size() > MAX_STEPS) {
            throw new ToolException(ToolError.INVALID_ARGS, "too many steps: " + rawSteps.size() + " > " + MAX_STEPS);
        }
        String dimension = AgentBodies.resolve(actor.id())
                .map(body -> body.position().dimension())
                .orElse("");
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
                        bodySlot(step)));
                default -> throw new ToolException(ToolError.INVALID_ARGS,
                        "step " + index + " has an unknown kind: " + kind);
            });
        }
        return steps;
    }

    private CompletableFuture<SequenceResult> run(ActorRef actor, List<SequenceStep> steps, int index,
                                                  List<SequenceResult.StepReport> reports, long deadline) {
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
        SequenceStep current = steps.get(index);
        Optional<ToolException> refusal = capabilityRefusal(actor, current);
        if (refusal.isPresent()) {
            reports.add(new SequenceResult.StepReport(current.kind(), false, refusal.get().getMessage()));
            return CompletableFuture.completedFuture(stop(reports, index, refusal.get().getMessage()));
        }
        return dispatch(actor, current).handle((outcome, failure) -> {
            boolean ok = failure == null && outcome != null && outcome.ok();
            String detail = failure != null
                    ? (failure.getMessage() == null ? "the step failed" : failure.getMessage())
                    : (outcome == null ? "the step produced no answer" : outcome.detail());
            reports.add(new SequenceResult.StepReport(current.kind(), ok, detail));
            if (!ok) {
                return CompletableFuture.completedFuture(stop(reports, index, detail));
            }
            return run(actor, steps, index + 1, reports, deadline);
        }).thenCompose(next -> next);
    }

    private static SequenceResult stop(List<SequenceResult.StepReport> reports, int index, String detail) {
        return new SequenceResult(List.copyOf(reports), index, detail);
    }

    private static Optional<ToolException> capabilityRefusal(ActorRef actor, SequenceStep step) {
        Optional<ToolException> refusal = AgentBodies.checkCapability(actor, capability(step));
        if (refusal.isPresent()) {
            return refusal;
        }
        if (step instanceof SequenceStep.ItemUse) {
            return AgentBodies.checkCapability(actor, BodyCapability.INVENTORY);
        }
        return Optional.empty();
    }

    private static BodyCapability capability(SequenceStep step) {
        if (step instanceof SequenceStep.Move) {
            return BodyCapability.MOVE;
        }
        if (step instanceof SequenceStep.Look) {
            return BodyCapability.LOOK;
        }
        if (step instanceof SequenceStep.Mine) {
            return BodyCapability.MINE;
        }
        if (step instanceof SequenceStep.Place) {
            return BodyCapability.PLACE;
        }
        if (step instanceof SequenceStep.Interact) {
            return BodyCapability.USE;
        }
        if (step instanceof SequenceStep.ItemUse) {
            return BodyCapability.USE;
        }
        if (step instanceof SequenceStep.Pickup) {
            return BodyCapability.INVENTORY;
        }
        return BodyCapability.INVENTORY;
    }

    private CompletableFuture<StepOutcome> dispatch(ActorRef actor, SequenceStep step) {
        Optional<AgentBodyActions> actions = AgentBodies.actions(actor.id());
        if (actions.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "this body has no action handlers"));
        }
        AgentBodyActions handlers = actions.get();
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
        return new ActorPosition(dimension, number(step, "x"), number(step, "y"), number(step, "z"));
    }

    private static BlockTarget block(String dimension, JsonObject step) {
        return new BlockTarget(dimension, integer(step, "x", Integer.MIN_VALUE),
                integer(step, "y", Integer.MIN_VALUE), integer(step, "z", Integer.MIN_VALUE));
    }

    private static ItemUseTarget itemUse(String dimension, JsonObject step) {
        if (!step.has("x") && !step.has("y") && !step.has("z")) {
            return ItemUseTarget.Air.INSTANCE;
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

    private static BodySlot bodySlot(JsonObject step) {
        String raw = step.has("target") ? text(step, "target") : BodySlot.MAIN_HAND.wireName();
        BodySlot slot = BodySlot.fromWireName(raw);
        if (slot == null) {
            throw new ToolException(ToolError.INVALID_ARGS,
                    "unknown equip target: " + raw + "; expected " + BodySlot.wireNameList());
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