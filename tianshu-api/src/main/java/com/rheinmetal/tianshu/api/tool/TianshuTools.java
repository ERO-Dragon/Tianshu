package com.rheinmetal.tianshu.api.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The process-wide tool registry: one place where the kernel, feature modules and third-party mods publish a
 * capability, and one place where the conversation agent or another mod looks it up.
 *
 * <p>Registration does not grant permission. The policy attached to a tool decides its default state, and the
 * category decides the policy ({@link ToolPolicy#forCategory}): local and server reads are on by default, server
 * writes are off until an operator enables them.</p>
 *
 * <p>Two ways in. A mod holds a {@link ToolHandle} (typed Java objects, no JSON) and calls it; the agent asks for
 * {@link #callJson}. Both go through the same checks and the same implementation. Server-side tools run directly
 * on the server and travel over the installed {@link ServerToolChannel} from the client; with no channel the call
 * fails fast with {@link ToolError#UNSUPPORTED_SIDE} instead of waiting for a component that is not there.</p>
 */
public final class TianshuTools {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Map<ToolId, TianshuTool<?, ?>> TOOLS = new ConcurrentHashMap<>();
    private static final Map<ToolId, Boolean> OVERRIDES = new ConcurrentHashMap<>();
    private static volatile ServerToolChannel serverChannel;

    private TianshuTools() {
    }

    /** Publishes a tool. A second tool with the same id is a programming error, not a silent replacement. */
    public static void register(TianshuTool<?, ?> tool) {
        Objects.requireNonNull(tool, "tool");
        TianshuTool<?, ?> existing = TOOLS.putIfAbsent(tool.id(), tool);
        if (existing != null && existing != tool) {
            throw new IllegalArgumentException("tool " + tool.id() + " is already registered");
        }
    }

    /** Removes a tool and its enable override. Intended for tests and for a mod that unloads. */
    public static void unregister(ToolId id) {
        TOOLS.remove(id);
        OVERRIDES.remove(id);
    }

    /** Installs the client-to-server bridge. Called by the platform when the server component is present. */
    public static void installServerChannel(ServerToolChannel channel) {
        serverChannel = Objects.requireNonNull(channel, "channel");
    }

    /** Removes the bridge; server-side tools become unavailable on this side again. */
    public static void uninstallServerChannel() {
        serverChannel = null;
    }

    public static boolean serverAvailable() {
        ServerToolChannel channel = serverChannel;
        return channel != null && channel.available();
    }

    /** Turns one tool on or off, overriding its category default. */
    public static void setEnabled(ToolId id, boolean enabled) {
        if (!TOOLS.containsKey(id)) {
            throw new IllegalArgumentException("cannot change the state of an unknown tool: " + id);
        }
        OVERRIDES.put(id, enabled);
    }

    /** Clears the override, returning the tool to its category default. */
    public static void clearEnabledOverride(ToolId id) {
        OVERRIDES.remove(id);
    }

    public static boolean isEnabled(ToolId id) {
        TianshuTool<?, ?> tool = TOOLS.get(id);
        if (tool == null) {
            return false;
        }
        Boolean override = OVERRIDES.get(id);
        return override != null ? override : tool.policy().enabledByDefault();
    }

    /**
     * Tools visible to {@code caller} on {@code side}, sorted by id.
     *
     * <p>The list includes disabled tools ({@link ToolSpec#enabled()} is false) so the agent can explain that a
     * capability exists but is switched off; it excludes tools the caller may never use and server tools that
     * cannot be reached from this side.</p>
     */
    public static List<ToolSpec> catalog(ToolCaller caller, ToolSide side) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(side, "side");
        List<ToolSpec> specs = new ArrayList<>();
        for (TianshuTool<?, ?> tool : TOOLS.values()) {
            if (tool.category().isServerSide() && side == ToolSide.CLIENT && !serverAvailable()) {
                continue;
            }
            if (tool.policy().permission() == ToolPermission.OPERATOR && !caller.operator()) {
                continue;
            }
            specs.add(new ToolSpec(tool.id(), tool.category(), tool.description(), tool.argsSchema(),
                    isEnabled(tool.id())));
        }
        specs.sort(Comparator.comparing(ToolSpec::id));
        return specs;
    }

    public static List<ToolSpec> catalog(ToolCaller caller) {
        return catalog(caller, ToolSide.CLIENT);
    }

    /**
     * A typed view of one tool. The class arguments must agree with what the tool declared; a mismatch is
     * reported by the call rather than producing a half-deserialized object.
     */
    public static <A, R> ToolHandle<A, R> handle(ToolId id, Class<A> argsType, Class<R> resultType) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(argsType, "argsType");
        Objects.requireNonNull(resultType, "resultType");
        return new Handle<>(id, argsType, resultType);
    }

    /**
     * Runs a tool with JSON arguments and returns the envelope the agent consumes:
     * {@code {"ok":true,"result":...}} or {@code {"ok":false,"code":"...","message":"..."}}.
     *
     * <p>Errors are values here, not exceptions: a model reasons about a structured failure better than about a
     * dropped future.</p>
     */
    public static CompletableFuture<JsonObject> callJson(ToolId id, JsonElement args, ToolCaller caller) {
        return callJson(id, args, caller, ToolSide.CLIENT);
    }

    public static CompletableFuture<JsonObject> callJson(ToolId id, JsonElement args, ToolCaller caller,
                                                         ToolSide side) {
        try {
            TianshuTool<?, ?> tool = requireUsable(id, caller, side);
            ToolContext context = new ToolContext(caller, side);
            if (tool.category().isServerSide() && side == ToolSide.CLIENT) {
                return callOverChannel(id, args, caller, tool.policy().timeoutMillis())
                        .handle((result, failure) -> failure == null
                                ? successEnvelope(result)
                                : failureEnvelope(failure));
            }
            Object typedArgs = GSON.fromJson(args, tool.argsType());
            return invokeRaw(tool, context, typedArgs)
                    .handle((result, failure) -> failure == null
                            ? successEnvelope(GSON.toJsonTree(result))
                            : failureEnvelope(failure));
        } catch (ToolException failure) {
            return CompletableFuture.completedFuture(failureEnvelope(failure));
        } catch (JsonParseException malformed) {
            return CompletableFuture.completedFuture(errorEnvelope(
                    ToolError.INVALID_ARGS, "arguments do not match " + id + ": " + malformed.getMessage()));
        }
    }

    /** The registered tool, or {@code null}. Read-only view for diagnostics. */
    public static TianshuTool<?, ?> find(ToolId id) {
        return TOOLS.get(id);
    }

    private static final class Handle<A, R> implements ToolHandle<A, R> {
        private final ToolId id;
        private final Class<A> argsType;
        private final Class<R> resultType;

        private Handle(ToolId id, Class<A> argsType, Class<R> resultType) {
            this.id = id;
            this.argsType = argsType;
            this.resultType = resultType;
        }

        @Override
        public ToolId id() {
            return id;
        }

        @Override
        public CompletableFuture<R> call(A args, ToolCaller caller, ToolSide side) {
            TianshuTool<?, ?> tool = TOOLS.get(id);
            if (tool == null) {
                return CompletableFuture.failedFuture(
                        new ToolException(ToolError.UNKNOWN_TOOL, "no tool named " + id + " on this side"));
            }
            if (!accepts(tool.argsType(), argsType)) {
                return CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                        "tool " + id + " declares arguments " + tool.argsType().getTypeName()
                                + ", this handle was built for " + argsType.getName()));
            }
            if (!accepts(tool.resultType(), resultType)) {
                return CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                        "tool " + id + " declares result " + tool.resultType().getTypeName()
                                + ", this handle was built for " + resultType.getName()));
            }
            return callTyped(tool, args, caller, side);
        }

        private static boolean accepts(Type declared, Class<?> requested) {
            if (declared instanceof Class<?> concrete) {
                return concrete.isAssignableFrom(requested);
            }
            return true;
        }
    }

    private static <A, R> CompletableFuture<R> callTyped(TianshuTool<?, ?> tool, A args, ToolCaller caller,
                                                         ToolSide side) {
        try {
            TianshuTool<?, ?> usable = requireUsable(tool.id(), caller, side);
            ToolContext context = new ToolContext(caller, side);
            if (usable.category().isServerSide() && side == ToolSide.CLIENT) {
                return callOverChannel(usable.id(), toJson(args), caller, usable.policy().timeoutMillis())
                        .thenApply(result -> GSON.fromJson(result, usable.resultType()));
            }
            return invokeRaw(usable, context, args);
        } catch (ToolException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static TianshuTool<?, ?> requireUsable(ToolId id, ToolCaller caller, ToolSide side) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(side, "side");
        TianshuTool<?, ?> tool = TOOLS.get(id);
        if (tool == null) {
            throw new ToolException(ToolError.UNKNOWN_TOOL, "no tool named " + id + " on this side");
        }
        if (!isEnabled(id)) {
            throw new ToolException(ToolError.DISABLED, "tool " + id + " is switched off");
        }
        if (tool.policy().permission() == ToolPermission.OPERATOR && !caller.operator()) {
            throw new ToolException(ToolError.NO_PERMISSION, "tool " + id + " needs an operator");
        }
        if (tool.category().isServerSide() && side == ToolSide.CLIENT && !serverAvailable()) {
            throw new ToolException(ToolError.UNSUPPORTED_SIDE,
                    "tool " + id + " needs the Tianshu server component");
        }
        return tool;
    }

    private static CompletableFuture<JsonElement> callOverChannel(ToolId id, JsonElement args, ToolCaller caller,
                                                                  long timeoutMillis) {
        ServerToolChannel channel = serverChannel;
        if (channel == null) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.UNSUPPORTED_SIDE, "tool " + id + " needs the server component"));
        }
        JsonElement safeArgs = args == null ? new JsonObject() : args;
        return channel.call(id, safeArgs, caller, timeoutMillis)
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .exceptionallyCompose(failure -> CompletableFuture.failedFuture(translate(failure, id)));
    }

    @SuppressWarnings("unchecked")
    private static <A, R> CompletableFuture<R> invokeRaw(TianshuTool<?, ?> tool, ToolContext context, Object args) {
        TianshuTool<A, R> typed = (TianshuTool<A, R>) tool;
        CompletableFuture<R> future;
        try {
            future = typed.invoke(context, (A) args);
        } catch (RuntimeException thrown) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.FAILED, "tool " + tool.id() + " threw: " + thrown, thrown));
        }
        if (future == null) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.FAILED, "tool " + tool.id() + " returned no future"));
        }
        long timeout = tool.policy().timeoutMillis();
        return future.orTimeout(timeout, TimeUnit.MILLISECONDS)
                .exceptionallyCompose(failure -> CompletableFuture.failedFuture(translate(failure, tool.id())));
    }

    private static ToolException translate(Throwable failure, ToolId id) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof ToolException toolFailure) {
            return toolFailure;
        }
        if (cause instanceof TimeoutException) {
            return new ToolException(ToolError.TIMEOUT, "tool " + id + " did not answer in time", cause);
        }
        return new ToolException(ToolError.FAILED, "tool " + id + " failed: " + cause, cause);
    }

    private static JsonElement toJson(Object args) {
        return args == null ? new JsonObject() : GSON.toJsonTree(args);
    }

    private static JsonObject successEnvelope(JsonElement result) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.add("result", result == null ? new JsonObject() : result);
        return envelope;
    }

    private static JsonObject failureEnvelope(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        ToolError code = cause instanceof ToolException toolFailure ? toolFailure.code() : ToolError.FAILED;
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        return errorEnvelope(code, message);
    }

    private static JsonObject errorEnvelope(ToolError code, String message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", false);
        envelope.addProperty("code", code.name());
        envelope.addProperty("message", message);
        return envelope;
    }
}