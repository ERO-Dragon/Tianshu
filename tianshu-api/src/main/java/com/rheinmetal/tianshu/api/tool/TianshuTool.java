package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * One capability the base offers to the conversation agent and to other mods alike.
 *
 * <p>A tool is registered once and called two ways: typed (another mod passes and receives Java objects) and as
 * JSON (the agent's arguments and results are the wire form). The two paths share this interface, so a tool
 * author writes the invocation once.</p>
 *
 * @param <A> the argument type
 * @param <R> the result type
 */
public interface TianshuTool<A, R> {

    ToolId id();

    ToolCategory category();

    default ToolSide executionSide() {
        return category().isServerSide() ? ToolSide.SERVER : ToolSide.CLIENT;
    }

    /** One sentence for the catalogue; the agent sees it. */
    String description();

    /** Concrete type of {@code A}, used to deserialize the agent's JSON arguments. */
    Type argsType();

    /** Concrete type of {@code R}, used to serialize the result for the agent. */
    Type resultType();

    /** Optional JSON Schema of the argument object, for the agent's tool definition. */
    default JsonObject argsSchema() {
        return null;
    }

    /**
     * The risk tier this tool needs when it acts <strong>as a player</strong>. Writes are survival-tier unless a tool
     * says otherwise; a tool that can give items, run commands or teleport should override this with
     * {@link ToolAutonomy#FULL}.
     */
    default ToolAutonomy requiredAutonomy() {
        return category().defaultAutonomy();
    }

    /**
     * The policy this tool runs under; by default it follows {@link #requiredAutonomy()}, so a tool that stays inside
     * survival needs no switch and a tool that needs {@link ToolAutonomy#FULL} is off until an operator opens it.
     */
    default ToolPolicy policy() {
        return ToolPolicy.forTier(requiredAutonomy());
    }

    /**
     * Runs the tool.
     *
     * <p>Implementations must not block the calling thread on game state they do not own; return a future that
     * completes on the thread that owns the state instead. The registry applies the timeout.</p>
     */
    CompletableFuture<R> invoke(ToolContext context, A args);
}