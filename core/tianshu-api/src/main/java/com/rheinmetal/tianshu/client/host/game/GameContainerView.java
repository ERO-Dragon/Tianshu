package com.rheinmetal.tianshu.client.host.game;

import java.util.List;

/**
 * What is inside the container the player currently has open.
 *
 * <p>Only the client's own open menu is read: nothing is requested from the server, and no other player's
 * inventory is visible. The list is a snapshot, not a live view.</p>
 */
public record GameContainerView(
        String title,
        List<GameContainerEntry> entries
) {
    public static final GameContainerView EMPTY = new GameContainerView("", List.of());

    public GameContainerView {
        title = title == null ? "" : title.trim();
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
