package com.rheinmetal.tianshu.client.host.game;

/**
 * One entry of the public player list.
 *
 * <p>This is what the client already displays in the tab list: name, latency and game mode. It deliberately
 * carries nothing about another player's inventory, position or health.</p>
 */
public record GamePlayerListEntry(
        String name,
        int latencyMillis,
        String gameMode
) {
    public GamePlayerListEntry {
        name = name == null ? "" : name.trim();
        latencyMillis = Math.max(0, latencyMillis);
        gameMode = gameMode == null ? "" : gameMode.trim();
    }
}
