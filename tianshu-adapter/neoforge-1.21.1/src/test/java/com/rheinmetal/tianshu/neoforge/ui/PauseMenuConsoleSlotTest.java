package com.rheinmetal.tianshu.neoforge.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PauseMenuConsoleSlotTest {

    /** The real vanilla geometry that produced a button drawn on top of the grid: 120-wide cells, 200-wide rows. */
    private static final List<PauseMenuConsoleSlot.Bounds> GRID = List.of(
            new PauseMenuConsoleSlot.Bounds(326, 150, 120, 20),
            new PauseMenuConsoleSlot.Bounds(450, 150, 120, 20),
            new PauseMenuConsoleSlot.Bounds(326, 174, 120, 20),
            new PauseMenuConsoleSlot.Bounds(450, 174, 120, 20),
            new PauseMenuConsoleSlot.Bounds(326, 198, 120, 20),
            new PauseMenuConsoleSlot.Bounds(450, 198, 120, 20));

    @Test
    void sitsBetweenTheGridAndTheFullWidthButtons() {
        List<PauseMenuConsoleSlot.Bounds> wide = List.of(
                new PauseMenuConsoleSlot.Bounds(326, 126, 200, 20),   // "Back to Game", above the grid
                new PauseMenuConsoleSlot.Bounds(326, 244, 200, 20),   // "Mods"
                new PauseMenuConsoleSlot.Bounds(326, 268, 200, 20));  // "Save and Quit"

        PauseMenuConsoleSlot.Slot slot = PauseMenuConsoleSlot.resolve(GRID, wide, 20, 4);

        assertEquals(326, slot.x());
        assertEquals(244, slot.y());
        assertEquals(244, slot.width());
        assertEquals(244, slot.y(), "the entry takes the first full-width slot below the grid");
        assertTrue(slot.y() > 218, "it must not overlap the grid block (which ends at 218)");
        // The button above the grid keeps its place; the two below it move down one row.
        assertEquals(List.of(1, 2), slot.shifted());
        assertEquals(24, slot.shift());
        int firstShiftedTop = wide.get(1).y() + slot.shift();
        assertEquals(slot.y() + slot.height() + 4, firstShiftedTop, "one row plus the vanilla 4px gap");
    }

    @Test
    void withoutFullWidthButtonsTheEntryStillClearsTheGrid() {
        PauseMenuConsoleSlot.Slot slot = PauseMenuConsoleSlot.resolve(GRID, List.of(), 20, 4);

        assertEquals(218 + 4, slot.y());
        assertEquals(244, slot.width());
        assertTrue(slot.shifted().isEmpty());
        assertEquals(0, slot.shift());
    }

    @Test
    void aScreenWithoutAGridHasNoAnchor() {
        assertNull(PauseMenuConsoleSlot.resolve(List.of(), List.of(), 20, 4));
    }
}
