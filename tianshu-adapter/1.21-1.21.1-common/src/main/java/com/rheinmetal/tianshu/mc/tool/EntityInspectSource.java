package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.EntityInspectPort;
import com.rheinmetal.tianshu.api.game.EntityInspectRequest;
import com.rheinmetal.tianshu.api.game.EntityInspectResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 entity read: type, name, position, health, tameness, the item in its hand.
 *
 * <p>Reach-bounded like the block read, and for the same reason: a body that cannot touch an entity should not be able to
 * inspect it from across the world. The dimension is reported so a caller can tell "far away" from "not in this level".</p>
 */
public final class EntityInspectSource implements EntityInspectPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;

    private final MinecraftServer server;

    public EntityInspectSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<EntityInspectResult> inspect(ActorRef actor, EntityInspectRequest request) {
        CompletableFuture<EntityInspectResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(inspectNow(actor, request)));
        return result;
    }

    private EntityInspectResult inspectNow(ActorRef actor, EntityInspectRequest request) {
        UUID id = parse(request.entityId());
        if (id == null) {
            return EntityInspectResult.failure("'" + request.entityId() + "' is not an entity id");
        }
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return EntityInspectResult.failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        Entity entity = find(id);
        if (entity == null) {
            return EntityInspectResult.failure("no entity with that id is loaded");
        }
        ServerLevel level = holder.level();
        if (entity.level() != level) {
            return EntityInspectResult.failure("that entity is in another dimension");
        }
        if (holder.position().distanceToSqr(entity.position()) > MAX_DISTANCE_SQUARED) {
            return EntityInspectResult.failure("that entity is out of reach");
        }
        LivingEntity living = entity instanceof LivingEntity candidate ? candidate : null;
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        ResourceLocation dimension = level.dimension().location();
        return new EntityInspectResult(
                entity.getUUID().toString(),
                type == null ? "" : type.toString(),
                entity.getName().getString(),
                dimension == null ? "" : dimension.toString(),
                entity.getX(), entity.getY(), entity.getZ(),
                living == null ? 0.0F : living.getHealth(),
                living == null ? 0.0F : living.getMaxHealth(),
                entity.isAlive(),
                living instanceof TamableAnimal tamable && tamable.isTame(),
                ownerOf(living),
                living != null && living.isBaby(),
                heldItemOf(living),
                "read");
    }

    private Entity find(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static String ownerOf(LivingEntity living) {
        if (!(living instanceof TamableAnimal tamable) || tamable.getOwnerUUID() == null) {
            return "";
        }
        return tamable.getOwnerUUID().toString();
    }

    private static String heldItemOf(LivingEntity living) {
        if (living == null) {
            return "";
        }
        ItemStack held = living.getMainHandItem();
        if (held.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(held.getItem());
        return key == null ? "" : key.toString();
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException notAnId) {
            return null;
        }
    }
}