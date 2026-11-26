package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.CancellationSignal;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.LocateRequest;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Finds the nearest structure or biome of a given id, from where an actor is.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides which position is the origin of the search.</p>
 *
 * <p>Exactly one of {@code structure} and {@code biome} is required. The two searches differ in what a negative answer
 * means, and the result carries {@code sampled} so a biome "not found" is not mistaken for "there is none": structures
 * are searched exactly, biomes are sampled on a grid.</p>
 */
final class WorldLocateOps {

    static final ToolPolicy POLICY = ToolPolicy.forCategory(ToolCategory.SERVER_READ).withTimeout(WorldLocatePort.TIMEOUT_MILLIS);

    static String describeScope(LocateRequest.Scope scope) {
        return scope == LocateRequest.Scope.GENERATED
                ? "Search fully generated chunks, including unloaded chunks in this save."
                : "Search the world, including terrain that has never been generated.";
    }

    static void describeSearch(JsonObject properties) {
        properties.add("structure", BodyToolSchemas.text("Structure id to find, for example minecraft:village_plains"));
        properties.add("biome", BodyToolSchemas.text("Biome id to find, for example minecraft:desert"));
        JsonObject radius = BodyToolSchemas.integer("Horizontal search radius in blocks");
        radius.addProperty("minimum", 1);
        radius.addProperty("maximum", LocateRequest.MAX_RADIUS_BLOCKS);
        radius.addProperty("default", LocateRequest.DEFAULT_RADIUS_BLOCKS);
        properties.add("radius", radius);
    }

    private WorldLocateOps() {
    }

    static CompletableFuture<LocateResult> locate(CancellationSignal cancellation, ActorRef actor, LocateRequest.Scope scope, String structure,
                                                  String biome, Integer radius) {
        LocateRequest request = new LocateRequest(structure, biome, radius == null ? LocateRequest.DEFAULT_RADIUS_BLOCKS : radius, scope);
        if (!request.singleTarget()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "world.locate needs exactly one of: a structure id or a biome id"));
        }
        return TianshuRuntimePorts.find(WorldLocatePort.class)
                .map(port -> port.locate(actor, request, cancellation)
                        .thenCompose(result -> result.searched()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                                        result.message().isBlank() ? "the search could not run" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "locating is only available on the server")));
    }
}