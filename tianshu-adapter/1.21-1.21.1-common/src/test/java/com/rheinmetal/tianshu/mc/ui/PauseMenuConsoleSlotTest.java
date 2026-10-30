package com.rheinmetal.tianshu.mc.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PauseMenuConsoleSlotTest {

    /** Real vanilla geometry: 120-wide grid cells, 200-wide rows, "Save and Quit" last at y=268. */
    private static final List<PauseMenuConsoleSlot.Bounds> GRID = List.of(
            new PauseMenuConsoleSlot.Bounds(326, 150, 120, 20),
            new PauseMenuConsoleSlot.Bounds(450, 150, 120, 20),
            new PauseMenuConsoleSlot.Bounds(326, 174, 120, 20),
            new PauseMenuConsoleSlot.Bounds(450, 174, 120, 20),
            new PauseMenuConsoleSlot.Bounds(326, 198, 120, 20),
            new PauseMenuConsoleSlot.Bounds(450, 198, 120, 20));

    private static final List<PauseMenuConsoleSlot.Bounds> WIDE = List.of(
            new PauseMenuConsoleSlot.Bounds(326, 126, 200, 20),   // "Back to Game", above the grid
            new PauseMenuConsoleSlot.Bounds(326, 244, 200, 20),   // NeoForge's "Mods"
            new PauseMenuConsoleSlot.Bounds(326, 268, 200, 20));  // "Save and Quit"

    @Test
    void sitsBelowEveryVanillaButton() {
        PauseMenuConsoleSlot.Bounds slot = PauseMenuConsoleSlot.resolve(GRID, WIDE, 20, 4);

        assertEquals(326, slot.x(), "same left edge as the buttons above it");
        assertEquals(244, slot.width(), "same width as the grid block");
        assertEquals(288 + 4, slot.y(), "one gap under the last button, not between buttons");
    }

    @Test
    void withoutFullWidthButtonsTheEntryStillClearsTheGrid() {
        PauseMenuConsoleSlot.Bounds slot = PauseMenuConsoleSlot.resolve(GRID, List.of(), 20, 4);

        assertEquals(218 + 4, slot.y());
        assertEquals(244, slot.width());
    }

    @Test
    void aScreenWithoutAGridHasNoAnchor() {
        assertNull(PauseMenuConsoleSlot.resolve(List.of(), WIDE, 20, 4));
    }
}
