package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CraftSettlementTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Actor implements ToolActor {
        final SimpleContainer bag = new SimpleContainer(36);
        final List<ItemStack> dropped = new ArrayList<>();
        public Container inventory() { return bag; }
        public Vec3 position() { return Vec3.ZERO; }
        public boolean operator() { return false; }
        public ServerLevel level() { return null; }
        // This double exists for the inventory arithmetic only, so it is never asked for the living entity behind it.
        public LivingEntity living() { return null; }
        public void drop(ItemStack stack) { dropped.add(stack); }
    }

    @Test
    void anExactIngredientCanDisplaceAnEarlierFlexibleOne() {
        var inventory = new SimpleContainer(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS));
        var either = net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
        var oak = net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_PLANKS);
        assertArrayEquals(new int[]{1, 0}, CraftSource.assignIngredients(inventory, List.of(either, oak)));
    }

    @Test
    void partialFitDropsOnlyTheRemainingOutput() {
        Actor actor = new Actor();
        for (int i = 0; i < 36; i++) actor.bag.setItem(i, new ItemStack(Items.STONE, 64));
        actor.bag.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        actor.bag.setItem(1, new ItemStack(Items.OAK_PLANKS, 62));
        CraftSource.settle(actor, new int[]{0}, new ItemStack(Items.OAK_PLANKS, 4), List.of());
        assertEquals(64, actor.bag.getItem(1).getCount());
        assertEquals(1, actor.bag.getItem(0).getCount());
        assertEquals(1, actor.dropped.size());
        assertTrue(actor.dropped.getFirst().is(Items.OAK_PLANKS));
        assertEquals(2, actor.dropped.getFirst().getCount());
    }

    @Test
    void emptyMilkBucketsReturnAlongsideTheCake() {
        Actor actor = new Actor();
        for (int i = 0; i < 3; i++) actor.bag.setItem(i, new ItemStack(Items.MILK_BUCKET));
        var bucket = new ItemStack(Items.BUCKET);
        CraftSource.settle(actor, new int[]{0, 1, 2}, new ItemStack(Items.CAKE), List.of(bucket, bucket, bucket));
        assertEquals(3, actor.bag.countItem(Items.BUCKET));
        assertEquals(1, actor.bag.countItem(Items.CAKE));
        assertEquals(0, actor.bag.countItem(Items.MILK_BUCKET));
        assertTrue(actor.dropped.isEmpty());
    }
}
