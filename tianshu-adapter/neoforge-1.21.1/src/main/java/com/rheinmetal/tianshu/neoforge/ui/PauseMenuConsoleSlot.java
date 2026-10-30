package com.rheinmetal.tianshu.neoforge.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the console entry sits in a vanilla pause menu.
 *
 * <p>Pure geometry on purpose: the placement is the part that goes wrong (a button drawn over the grid, or an
 * entry stranded at the wrong edge), and none of it needs a {@code Screen} to reason about. The adapter feeds it
 * the rectangles it can already see and applies the answer.</p>
 *
 * <p>Vanilla lays the pause menu out as a two-column grid of narrow buttons with full-width buttons below it
 * ("Mods", then "Save and Quit"). The console entry belongs in the free slot directly under the grid, with the
 * full-width buttons pushed down by exactly one row — the same left edge, width and row gap as its neighbours.</p>
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
     * @param shifted indexes into the caller's full-width button list that must move down by {@code shift}
     * @param shift   how far those buttons move, in GUI units
     */
    public record Slot(int x, int y, int width, int height, List<Integer> shifted, int shift) {
    }

    /**
     * @return the slot to occupy, or {@code null} when the screen has no narrow grid at all — the caller then has
     *         nothing to anchor to and places the entry on its own
     */
    public static Slot resolve(List<Bounds> grid, List<Bounds> wide, int rowHeight, int gap) {
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

        // Everything at or below the grid block: the full-width entries that follow it. Anything above the grid
        // ("Back to Game") keeps its place — the console entry is inserted after the grid, never before it.
        List<Integer> below = new ArrayList<>();
        int anchor = Integer.MAX_VALUE;
        for (int index = 0; index < wide.size(); index++) {
            Bounds bounds = wide.get(index);
            if (bounds.y() >= bottom) {
                below.add(index);
                anchor = Math.min(anchor, bounds.y());
            }
        }
        if (below.isEmpty()) {
            return new Slot(left, bottom + gap, right - left, rowHeight, List.of(), 0);
        }

        int y = Math.max(anchor, bottom + gap);
        int shift = y + rowHeight + gap - anchor;
        return new Slot(left, y, right - left, rowHeight, List.copyOf(below), shift);
    }
}
