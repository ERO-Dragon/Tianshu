package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Resolves the item storage of a block, in one place so every tool agrees on which one it is.
 *
 * <p>The order matters and is not negotiable: the loader's handler wins when there is one, because it is the machine's own
 * view of its slots — a machine that also happens to implement {@code Container} (many do, for menu compatibility) would
 * otherwise be read through a compatibility wrapper whose slot indices need not be the real ones.</p>
 *
 * <p>Agreement is the point: {@code container.inspect} reports slot numbers and {@code container.transfer} moves by them,
 * so both have to resolve the same store for the same block. Two resolutions that disagree produce moves into a slot the
 * caller was never shown.</p>
 */
public final class ItemStores {

    private ItemStores() {
    }

    public static ItemStore of(ServerLevel level, BlockPos pos, BlockStorageProbe probe) {
        if (level == null || pos == null) {
            return ItemStore.NONE;
        }
        BlockStorageProbe effective = probe == null ? BlockStorageProbe.NONE : probe;
        ItemStore handled = effective.items(level, pos);
        if (handled != null && handled.size() > 0) {
            return handled;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) {
            return new ContainerStore(container);
        }
        return ItemStore.NONE;
    }
}