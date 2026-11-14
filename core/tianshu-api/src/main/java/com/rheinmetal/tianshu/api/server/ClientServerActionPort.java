package com.rheinmetal.tianshu.api.server;

import java.util.concurrent.CompletableFuture;

/**
 * Requests that only the server can carry out (the optional server component decides whether it will).
 *
 * <p>Every method is a request, not an effect: the future completes with the server's answer and the action may
 * have been denied, disabled or failed. The host never applies a world change on the client, so a client connected
 * to a server without the component gets a completed failure instead of a local-only illusion.</p>
 *
 * <p>The action set is a fixed whitelist owned by the platform; there is no generic command entry. Each action is
 * behind its own server-config switch (default off) and the first version requires operator permission.</p>
 */
public interface ClientServerActionPort {

    /** Gives the player {@code count} of {@code itemId} (registry id); overflow is dropped at the player's feet. */
    CompletableFuture<ServerActionResult> giveItem(String itemId, int count);

    /** Moves the player to the given coordinates in the current dimension. */
    CompletableFuture<ServerActionResult> teleport(double x, double y, double z);

    /** Sets the current level's time: {@code day}, {@code noon}, {@code night}, {@code midnight}, or {@code ticks:<n>}. */
    CompletableFuture<ServerActionResult> setTime(String time);

    /** Sets the current level's weather: {@code clear}, {@code rain} or {@code thunder}. */
    CompletableFuture<ServerActionResult> setWeather(String weather);

    /** Spawns {@code count} entities of {@code entityTypeId} at the player's position. */
    CompletableFuture<ServerActionResult> spawnEntity(String entityTypeId, int count);

    /** Broadcasts one line to every player on the server. Subject to the server's switch and rate limit. */
    CompletableFuture<ServerActionResult> broadcast(String text);

    /**
     * The actions the connected server currently allows, as the server announced them when the player joined.
     *
     * <p>A feature that offers actions to a model uses this to avoid offering a tool the server would reject. The
     * set is empty until the announcement arrives (or when the connected server has no Tianshu component), so
     * {@link #serverActionsAvailable()} distinguishes "not announced yet" from "announced, nothing enabled".</p>
     */
    java.util.Set<String> availableActions();

    /** Whether the connected server announced an action catalog at all. */
    boolean serverActionsAvailable();
}
