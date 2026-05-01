package com.rheinmetal.tianshu.function.CraftingGraph;

import java.util.UUID;

public final class SlotHitResult {

    private final UUID nodeUuid;
    private final SlotViewData slot;

    public SlotHitResult(UUID nodeUuid, SlotViewData slot) {
        this.nodeUuid = nodeUuid;
        this.slot = slot;
    }

    public UUID getNodeUuid() { return nodeUuid; }
    public SlotViewData getSlot() { return slot; }
}
