package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.ItemInspectPort;
import com.rheinmetal.tianshu.api.game.ItemInspectRequest;
import com.rheinmetal.tianshu.api.game.ItemInspectResult;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 stack read: durability, components and the name of one inventory slot.
 *
 * <p>Durability is reported as both the damage taken and what is left, because "damage 3 of 131" makes a caller do
 * arithmetic and "128 left" is the number the decision needs.</p>
 *
 * <p>Components are listed by id rather than interpreted. A modded stack carries data this platform cannot understand, and
 * naming it honestly is better than guessing at its meaning; enchantments are the one family spelled out because "is the
 * pickaxe enchanted" is a decision.</p>
 */
public final class ItemInspectSource implements ItemInspectPort {

    private final MinecraftServer server;

    public ItemInspectSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<ItemInspectResult> inspect(ActorRef actor, ItemInspectRequest request) {
        CompletableFuture<ItemInspectResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(inspectNow(actor, request)));
        return result;
    }

    private ItemInspectResult inspectNow(ActorRef actor, ItemInspectRequest request) {
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return ItemInspectResult.failure(request.slot(), actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        Container inventory = holder.inventory();
        if (inventory == null) {
            return ItemInspectResult.failure(request.slot(), "that agent body has no inventory");
        }
        if (request.slot() < 0 || request.slot() >= inventory.getContainerSize()) {
            return ItemInspectResult.failure(request.slot(), "that slot does not exist");
        }
        ItemStack stack = inventory.getItem(request.slot());
        if (stack.isEmpty()) {
            return ItemInspectResult.failure(request.slot(), "the slot is empty");
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        boolean damageable = stack.isDamageableItem();
        int damage = stack.getDamageValue();
        int maxDamage = stack.getMaxDamage();
        return new ItemInspectResult(
                request.slot(),
                itemId == null ? "" : itemId.toString(),
                stack.getCount(),
                stack.getMaxStackSize(),
                damageable,
                damage,
                maxDamage,
                damageable ? Math.max(0, maxDamage - damage) : 0,
                stack.has(DataComponents.CUSTOM_NAME) ? stack.getHoverName().getString() : "",
                enchantmentsOf(stack),
                componentsOf(stack),
                "read");
    }

    private static List<String> enchantmentsOf(ItemStack stack) {
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null || enchantments.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (var enchantment : enchantments.keySet()) {
            ResourceLocation id = enchantment.unwrapKey().map(key -> key.location()).orElse(null);
            if (id != null) {
                ids.add(id + " " + enchantments.getLevel(enchantment));
            }
        }
        return ids;
    }

    private static List<String> componentsOf(ItemStack stack) {
        List<String> ids = new ArrayList<>();
        for (DataComponentType<?> type : stack.getComponents().keySet()) {
            ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (id != null) {
                ids.add(id.toString());
            }
        }
        return ids;
    }
}