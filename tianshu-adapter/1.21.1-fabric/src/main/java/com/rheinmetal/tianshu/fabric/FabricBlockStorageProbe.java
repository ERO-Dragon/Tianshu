package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.game.EnergyView;
import com.rheinmetal.tianshu.api.game.FluidTankView;
import com.rheinmetal.tianshu.mc.tool.BlockStorageProbe;
import com.rheinmetal.tianshu.mc.tool.ItemStore;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric's half of the block read: fluid storage through the transfer API.
 *
 * <p>Fabric has no single capability object: {@link FluidStorage#SIDED} is a lookup that each mod registers into, so
 * asking it is exactly asking "does anything here handle fluids". The side is null for the same reason as NeoForge's
 * unsided lookup — that is the view a player sees, not the view of one face.</p>
 *
 * <p><b>Energy is not read here.</b> Fabric's item and fluid transfer API ships with Fabric API, but energy does not:
 * the ecosystem standard is Reborn Energy ({@code team.reborn.energy.api}), a separate library. The base platform does
 * not depend on it, so on Fabric a machine's energy buffer reports as absent rather than as zero. That is a stated
 * limitation, not a silent one — and the seam is one method, so adding it is a dependency plus a few lines when the
 * dependency is worth taking on.</p>
 */
public final class FabricBlockStorageProbe implements BlockStorageProbe {

    /**
     * Null on purpose: Fabric''s transfer API exposes storages as a sequence of views, not as indexed slots, and the tool
     * contract is slot-addressed (inspect reports a slot index, transfer moves by it). Mapping view order onto slot
     * numbers would look like it works and then move items into a slot the caller was never shown, so this side keeps
     * the vanilla container contract instead and reports that it adds nothing.
     */
    @Override
    public ItemStore items(ServerLevel level, BlockPos pos) {
        return null;
    }

    @Override
    public List<FluidTankView> fluids(ServerLevel level, BlockPos pos) {
        Storage<FluidVariant> storage = FluidStorage.SIDED.find(level, pos, null);
        if (storage == null) {
            return List.of();
        }
        List<FluidTankView> tanks = new ArrayList<>();
        int tank = 0;
        for (StorageView<FluidVariant> view : storage) {
            long amount = view.getAmount();
            FluidVariant variant = view.getResource();
            if (amount > 0L && !variant.isBlank()) {
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(variant.getFluid());
                tanks.add(new FluidTankView(tank, id == null ? "" : id.toString(), amount, view.getCapacity()));
            }
            tank++;
        }
        return tanks;
    }

    @Override
    public EnergyView energy(ServerLevel level, BlockPos pos) {
        // See the class note: Fabric's energy API is not part of Fabric API, so there is nothing to ask.
        return EnergyView.EMPTY;
    }
}