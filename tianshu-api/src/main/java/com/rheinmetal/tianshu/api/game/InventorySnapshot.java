package com.rheinmetal.tianshu.api.game;

import java.util.List;

/** The local player's occupied inventory slots, sorted by slot index. */
public record InventorySnapshot(List<InventoryEntry> slots) {
}
