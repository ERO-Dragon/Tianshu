package com.rheinmetal.tianshu.api.game;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one block is and what it holds, in a single read.
 *
 * <p>The point of this shape is that it is <em>capability probed</em>, not block-type matched: a modded machine the
 * platform has never heard of answers with its items, its tanks and its buffer if it exposes them, and the model can
 * work it without a per-mod adapter. A machine that exposes nothing still answers with its id and state, which is
 * enough to say "I do not know this one" instead of guessing.</p>
 *
 * <p>Empty collections mean "not present", not "present but empty": an inspected chest that happens to be empty and a
 * granite block both report no items, and both are correct.</p>
 */
public record BlockInspectResult(
        String blockId,
        String blockEntityId,
        Map<String, String> state,
        List<InventoryEntry> items,
        List<FluidTankView> fluids,
        EnergyView energy,
        String message) {

    public BlockInspectResult {
        Objects.requireNonNull(blockId, "blockId");
        blockEntityId = blockEntityId == null ? "" : blockEntityId;
        state = state == null ? Map.of() : Map.copyOf(state);
        items = items == null ? List.of() : List.copyOf(items);
        fluids = fluids == null ? List.of() : List.copyOf(fluids);
        energy = energy == null ? EnergyView.EMPTY : energy;
        message = message == null ? "" : message;
    }

    /** A refusal: nothing was read, and the message says why. */
    public static BlockInspectResult failure(String message) {
        return new BlockInspectResult("", "", Map.of(), List.of(), List.of(), EnergyView.EMPTY, message);
    }

    /** True when the block itself was read, whatever it turned out to hold. */
    public boolean inspected() {
        return !blockId.isBlank();
    }

    /** True when the block exposes anything at all beyond its own identity. */
    public boolean holdsSomething() {
        return !items.isEmpty() || !fluids.isEmpty() || energy.present();
    }
}