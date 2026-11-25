package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.server.ServerActionExecutionPort;
import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The server actions a model may ask for, as tools.
 *
 * <p>They are server tools in the platform's sense: the client ship the declarations, the server owns the
 * implementations, and only a server that names one in its catalogue makes it callable. Execution goes through
 * {@link ServerActionExecutionPort}, so there is one whitelist and one set of gates; nothing here holds a second copy of
 * the server's switches.</p>
 *
 * <p>The table below is the whitelist. Adding an action means adding one row here, one branch in the adapter's
 * executor, and one switch in its configuration — no new plumbing.</p>
 *
 * <p>Speaking is deliberately not in this table: chat is what any player may do, so {@code server.say} is a local tool
 * ({@link SayTool}).</p>
 */
public final class ServerActionTools {

    /** How one action is described to the model and what it needs. */
    private record Spec(String path, String name, String description, List<String> required, List<String> numbers,
                        Map<String, List<String>> enums, ToolAutonomy tier) {
    }

    private static final List<Spec> ACTIONS = List.of(
            new Spec("server.teleport", "teleport",
                    "Teleports the requesting player to coordinates in the current dimension. "
                            + "Cheat-grade: needs the server's teleport switch and an operator.",
                    List.of("x", "y", "z"), List.of("x", "y", "z"), Map.of(), ToolAutonomy.FULL),
            new Spec("server.weather", "set_weather",
                    "Sets the current level's weather: clear, rain or thunder. "
                            + "Cheat-grade: needs the server's weather switch and an operator.",
                    List.of("weather"), List.of(), Map.of("weather", List.of("clear", "rain", "thunder")),
                    ToolAutonomy.FULL),
            new Spec("server.time", "set_time",
                    "Sets the current level's time: day, noon, night, midnight or ticks:<n>. "
                            + "Cheat-grade: needs the server's time switch and an operator.",
                    List.of("time"), List.of(), Map.of(), ToolAutonomy.FULL),
            new Spec("server.give", "give_item",
                    "Gives the player items by registry id; overflow drops at their feet. "
                            + "Cheat-grade: needs the server's give switch and an operator.",
                    List.of("item", "count"), List.of("count"), Map.of(), ToolAutonomy.FULL),
            new Spec("server.spawn_entity", "spawn_entity",
                    "Spawns entities by registry id at the player's position. "
                            + "Cheat-grade: needs the server's spawn switch and an operator.",
                    List.of("type", "count"), List.of("count"), Map.of(), ToolAutonomy.FULL),
            new Spec("server.broadcast", "broadcast",
                    "Broadcasts one line to every player as a system notice — the equivalent of /say. "
                            + "Cheat-grade: needs the server's broadcast switch and an operator.",
                    List.of("text"), List.of(), Map.of(), ToolAutonomy.FULL),
            new Spec("server.gamemode", "gamemode",
                    "Sets the player's game mode. Cheat-grade: needs the server's gamemode switch and an operator.",
                    List.of("value"), List.of(),
                    Map.of("value", List.of("survival", "creative", "adventure", "spectator")), ToolAutonomy.FULL),
            new Spec("server.effect", "effect",
                    "Gives the player a status effect for a number of seconds, or clears every effect with 'clear'. "
                            + "Cheat-grade: needs the server's effect switch and an operator.",
                    List.of("effect"), List.of("seconds", "amplifier"), Map.of(), ToolAutonomy.FULL),
            new Spec("server.xp", "xp",
                    "Changes the player's experience level by a number of levels (negative removes). "
                            + "Cheat-grade: needs the server's xp switch and an operator.",
                    List.of("levels"), List.of("levels"), Map.of(), ToolAutonomy.FULL),
            new Spec("server.setblock", "setblock",
                    "Places one block by registry id at absolute coordinates. "
                            + "Cheat-grade: needs the server's setblock switch and an operator.",
                    List.of("x", "y", "z", "block"), List.of("x", "y", "z"), Map.of(), ToolAutonomy.FULL),
            new Spec("server.kill", "kill",
                    "Removes one entity in the player's level by its id. "
                            + "Cheat-grade: needs the server's kill switch and an operator.",
                    List.of("id"), List.of(), Map.of(), ToolAutonomy.FULL),
            new Spec("server.enchant", "enchant",
                    "Enchants the item in the player's main hand. "
                            + "Cheat-grade: needs the server's enchant switch and an operator.",
                    List.of("enchantment", "level"), List.of("level"), Map.of(), ToolAutonomy.FULL),
            new Spec("server.difficulty", "difficulty",
                    "Sets the server difficulty. Cheat-grade: needs the server's difficulty switch and an operator.",
                    List.of("value"), List.of(),
                    Map.of("value", List.of("peaceful", "easy", "normal", "hard")), ToolAutonomy.FULL),
            new Spec("server.gamerule", "gamerule",
                    "Sets one whitelisted game rule: doDaylightCycle, doMobSpawning, keepInventory, mobGriefing, "
                            + "naturalRegeneration, doImmediateRespawn. "
                            + "Cheat-grade: needs the server's gamerule switch and an operator.",
                    List.of("rule", "value"), List.of(), Map.of(), ToolAutonomy.FULL));

    private ServerActionTools() {
    }

    /** Every action tool instance: the server registers them, the client declares them. */
    public static List<TianshuTool<?, ?>> tools() {
        List<TianshuTool<?, ?>> tools = new ArrayList<>(ACTIONS.size());
        for (Spec spec : ACTIONS) {
            tools.add(new ActionTool(spec));
        }
        return tools;
    }

    /**
     * Points each action tool's enabled state at the server's own switch for that action.
     *
     * <p>The catalogue a server pushes drives the client's tool table, so a tool whose switch is off must not be
     * advertised as callable. Called at server start and again whenever the switches are reloaded.</p>
     */
    public static void applySwitches(java.util.function.Function<String, Boolean> switchFor) {
        for (Spec spec : ACTIONS) {
            TianshuTools.setEnabled(ToolId.of("minecraft", spec.path()), Boolean.TRUE.equals(switchFor.apply(spec.name())));
        }
    }

    /** The ids this family owns, for a caller that clears or inspects the registry. */
    public static List<ToolId> ids() {
        List<ToolId> ids = new ArrayList<>(ACTIONS.size());
        for (Spec spec : ACTIONS) {
            ids.add(ToolId.of("minecraft", spec.path()));
        }
        return ids;
    }

    /** One action, described by its {@link Spec}: identity, schema and the port call are all derived. */
    private static final class ActionTool implements TianshuTool<JsonObject, JsonObject> {

        private final ToolId id;
        private final Spec spec;

        private ActionTool(Spec spec) {
            this.id = ToolId.of("minecraft", spec.path());
            this.spec = spec;
        }

        @Override
        public ToolId id() {
            return id;
        }

        @Override
        public ToolCategory category() {
            // A world change: off until an operator turns the tool on, and operator-gated on both sides.
            return ToolCategory.SERVER_WRITE;
        }

        @Override
        public ToolAutonomy requiredAutonomy() {
            return spec.tier();
        }

        @Override
        public String description() {
            return spec.description();
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
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            for (String name : spec.required()) {
                JsonObject property = new JsonObject();
                property.addProperty("type", spec.numbers().contains(name) ? "number" : "string");
                List<String> allowed = spec.enums().get(name);
                if (allowed != null) {
                    JsonArray values = new JsonArray();
                    allowed.forEach(values::add);
                    property.add("enum", values);
                }
                properties.add(name, property);
            }
            schema.add("properties", properties);
            JsonArray required = new JsonArray();
            spec.required().forEach(required::add);
            schema.add("required", required);
            return schema;
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            JsonObject safe = args == null ? new JsonObject() : args;
            Map<String, String> params = new LinkedHashMap<>();
            for (String name : spec.required()) {
                var value = safe.get(name);
                if (value == null || !value.isJsonPrimitive()) {
                    return failed(ToolError.INVALID_ARGS, "argument " + name + " is missing");
                }
                params.put(name, value.getAsString().trim());
            }
            ServerActionExecutionPort port = TianshuRuntimePorts.find(ServerActionExecutionPort.class).orElse(null);
            if (port == null) {
                return failed(ToolError.UNSUPPORTED_SIDE, "this side cannot carry out server actions");
            }
            try {
                ServerActionResult result = port.execute(context.caller().id(), spec.name(), params);
                return CompletableFuture.completedFuture(envelope(result));
            } catch (ToolException refused) {
                return CompletableFuture.failedFuture(refused);
            }
        }
    }

    private static CompletableFuture<JsonObject> failed(ToolError error, String message) {
        return CompletableFuture.failedFuture(new ToolException(error, message));
    }

    /** The server's answer, mapped onto the tool envelope; a refusal keeps its own code rather than becoming FAILED. */
    private static JsonObject envelope(ServerActionResult result) {
        if (result.status() == ServerActionResult.Status.OK) {
            JsonObject ok = new JsonObject();
            ok.addProperty("ok", true);
            ok.addProperty("detail", result.message());
            return ok;
        }
        throw new ToolException(switch (result.status()) {
            case DENIED -> ToolError.NO_PERMISSION;
            case DISABLED -> ToolError.DISABLED;
            case INVALID -> ToolError.INVALID_ARGS;
            case FAILED, OK -> ToolError.FAILED;
        }, result.message());
    }
}