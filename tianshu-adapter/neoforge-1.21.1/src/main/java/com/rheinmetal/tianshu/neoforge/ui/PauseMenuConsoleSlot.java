package com.rheinmetal.tianshu.neoforge.ui;

import java.util.List;

/**
 * Where the console entry sits in a vanilla pause menu.
 *
 * <p>Pure geometry on purpose: the placement is the part that goes wrong (an entry splitting the game's own
 * menu, or drawn over the grid), and none of it needs a {@code Screen} to reason about. The adapter feeds it the
 * rectangles it can already see and applies the answer.</p>
 *
 * <p>The entry goes below every vanilla button, not between them: the pause menu is the game's own list, and
 * inserting into it moves "Mods" and "Save and Quit" around. Nothing is shifted — there is nothing below the
 * last button to make room for.</p>
 */
public final class PauseMenuConsoleSlot {

    /** A widget rectangle in GUI units. */
    public record Bounds(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    /**
     * @return the slot to occupy, or {@code null} when the screen has no narrow grid at all — the caller then has
     *         nothing to anchor to and places the entry on its own
     */
    public static Bounds resolve(List<Bounds> grid, List<Bounds> wide, int rowHeight, int gap) {
        if (grid.isEmpty()) {
            return null;
        }
        int left = Integer.MAX_VALUE;
        int right = 0;
        int bottom = 0;
        for (Bounds bounds : grid) {
            left = Math.min(left, bounds.x());
            right = Math.max(right, bounds.right());
            bottom = Math.max(bottom, bounds.bottom());
        }
        // Below the last full-width button when there is one ("Save and Quit" in vanilla, plus NeoForge's "Mods"),
        // otherwise directly under the grid.
        for (Bounds bounds : wide) {
            bottom = Math.max(bottom, bounds.bottom());
        }
        return new Bounds(left, bottom + gap, right - left, rowHeight);
    }
}
