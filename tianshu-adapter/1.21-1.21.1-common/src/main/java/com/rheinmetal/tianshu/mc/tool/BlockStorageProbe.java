package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.EnergyView;
import com.rheinmetal.tianshu.api.game.FluidTankView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * What a loader can read out of a block beyond vanilla containers.
 *
 * <p>Items are vanilla: a block entity that implements {@code Container} is readable without any loader API, which is
 * why only fluids and energy live behind this seam. Those two differ per loader (NeoForge capabilities versus Fabric's
 * transfer API), so the neutral code asks this question and each loader answers it.</p>
 *
 * <p>There is no "unknown" branch on purpose: a block with no handler answers with an empty list and
 * {@link EnergyView#EMPTY}, which is the same answer a granite block gives. Whether a modded machine can be seen is
 * decided by whether it exposes a handler, not by whether the platform has heard of its block id.</p>
 */
public interface BlockStorageProbe {

    /** A probe for a loader that exposes neither: every block answers "nothing here". */
    BlockStorageProbe NONE = new BlockStorageProbe() {
        @Override
        public List<FluidTankView> fluids(ServerLevel level, BlockPos pos) {
            return List.of();
        }

        @Override
        public EnergyView energy(ServerLevel level, BlockPos pos) {
            return EnergyView.EMPTY;
        }
    };

    /**
     * The block's item storage when it exposes one beyond the vanilla container contract, or null when it does not.
     *
     * <p>Null, not {@link ItemStore#NONE}: "this loader has nothing to add" and "this block has no items" must stay
     * distinguishable, because the caller's next step differs — one falls back to the vanilla container, the other
     * reports an empty block.</p>
     */
    default ItemStore items(ServerLevel level, BlockPos pos) {
        return null;
    }

    List<FluidTankView> fluids(ServerLevel level, BlockPos pos);

    EnergyView energy(ServerLevel level, BlockPos pos);
}