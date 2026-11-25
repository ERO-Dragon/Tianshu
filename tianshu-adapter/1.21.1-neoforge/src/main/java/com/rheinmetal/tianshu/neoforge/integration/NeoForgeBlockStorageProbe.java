package com.rheinmetal.tianshu.neoforge.integration;

import com.rheinmetal.tianshu.api.game.EnergyView;
import com.rheinmetal.tianshu.api.game.FluidTankView;
import com.rheinmetal.tianshu.mc.tool.BlockStorageProbe;
import com.rheinmetal.tianshu.mc.tool.ItemStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge's half of the block read: fluid handlers and energy storage through the capability system.
 *
 * <p>The capability is asked for with no side, which NeoForge treats as "the null side" — the answer a machine gives
 * when a player looks at it in a GUI rather than through a particular face. A machine that only exposes one face still
 * answers that way, and a machine that exposes nothing answers nothing.</p>
 */
public final class NeoForgeBlockStorageProbe implements BlockStorageProbe {

    @Override
    public ItemStore items(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        return handler == null ? null : new NeoForgeItemStore(handler);
    }

    @Override
    public List<FluidTankView> fluids(ServerLevel level, BlockPos pos) {
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (handler == null) {
            return List.of();
        }
        List<FluidTankView> tanks = new ArrayList<>();
        int tankCount = handler.getTanks();
        for (int tank = 0; tank < tankCount; tank++) {
            FluidStack fluid = handler.getFluidInTank(tank);
            if (fluid.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
            tanks.add(new FluidTankView(tank, id == null ? "" : id.toString(),
                    fluid.getAmount(), handler.getTankCapacity(tank)));
        }
        return tanks;
    }

    @Override
    public EnergyView energy(ServerLevel level, BlockPos pos) {
        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (storage == null) {
            return EnergyView.EMPTY;
        }
        int capacity = Math.max(0, storage.getMaxEnergyStored());
        int stored = Math.clamp(storage.getEnergyStored(), 0, capacity);
        return new EnergyView(stored, capacity);
    }
}