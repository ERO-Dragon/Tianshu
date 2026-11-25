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
        return category() == ToolCategory.SERVER_WRITE ? ToolAutonomy.CONSERVATIVE : ToolAutonomy.READ_ONLY;
    }

    /**
     * The risk tier this tool needs when it acts <strong>through a body</strong>.
     *
     * <p>A body write is a survival action: a carrier mining is the body doing its job, not the player cheating. That is
     * why the default follows the category instead of the player scale, and why a read stays a read.</p>
     *
     * <p>A tool whose body use is privileged has to say so — either here, when the whole tool is privileged, or, when
     * only one of its sub-modes is (a move mode that flies or teleports), by checking
     * {@link ToolContext#allowedBodyAutonomy()} for that sub-mode. Walking is fine; a teleport is not.</p>
     *
     * <p>A tool that can serve both actor kinds declares both scales: {@code block.place} is survival work either way, so
     * it names {@link BodyAutonomy#SURVIVAL} here as well.</p>
     */
    default BodyAutonomy requiredBodyAutonomy() {
        return category() == ToolCategory.BODY_WRITE ? BodyAutonomy.SURVIVAL : BodyAutonomy.READ;
    }

    /**
     * The level that actually has to be permitted for a call: the stricter of the two declarations.
     *
     * <p>Both scales rank the server's one policy knob, so the stricter requirement is the one that decides. A tool that
     * only acts as a player declares {@link BodyAutonomy#READ} and is unaffected; a body write declares
     * {@link BodyAutonomy#SURVIVAL} and is therefore refused by a {@code READ_ONLY} server — which is the defect this
     * method exists to close.</p>
     */
    default ToolAutonomy requiredLevel() {
        ToolAutonomy player = requiredAutonomy();
        ToolAutonomy body = requiredBodyAutonomy().asToolAutonomy();
        return player.permits(body) ? player : body;
    }

    /** The policy this tool runs under; by default it follows the category. */
    default ToolPolicy policy() {
        return ToolPolicy.forCategory(category());
    }

    /**
     * Runs the tool.
     *
     * <p>Implementations must not block the calling thread on game state they do not own; return a future that
     * completes on the thread that owns the state instead. The registry applies the timeout.</p>
     */
    CompletableFuture<R> invoke(ToolContext context, A args);
}