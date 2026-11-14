package com.rheinmetal.tianshu.api.tool;

import java.util.concurrent.CompletableFuture;

/**
 * A typed view of one registered tool, for callers that hold Java objects rather than JSON.
 *
 * <p>Obtain it with {@link TianshuTools#handle(ToolId, Class, Class)}. The class arguments are the contract: a
 * call deserializes and serializes against them, and a mismatch with the tool's declared types fails the call
 * instead of silently producing a half-filled object.</p>
 *
 * @param <A> argument type
 * @param <R> result type
 */
public interface ToolHandle<A, R> {

    ToolId id();

    /** Calls the tool from the client side. */
    default CompletableFuture<R> call(A args, ToolCaller caller) {
        return call(args, caller, ToolSide.CLIENT);
    }

    CompletableFuture<R> call(A args, ToolCaller caller, ToolSide side);
}