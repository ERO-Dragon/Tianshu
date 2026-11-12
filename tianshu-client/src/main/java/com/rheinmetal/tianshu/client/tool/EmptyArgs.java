package com.rheinmetal.tianshu.client.tool;

/**
 * The wire shape of a tool that takes no arguments.
 *
 * <p>It exists so {@code argsType()} is a concrete class; the agent sends an empty object and gson maps it here.</p>
 */
public final class EmptyArgs {
}