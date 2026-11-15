package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentBodyProvider;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The server-side agent bodies of this adapter. The registry owns the carriers and their inventories; the platform
 * only sees {@link AgentBody} snapshots through {@link #resolve(String)}.
 *
 * <p>Bodies live in memory and are not persisted: a restart drops them, which is the honest state until the
 * inventory format is decided. A removed entity makes its body disappear rather than turning into a still body.</p>
 */
public final class BodyRegistry implements AgentBodyProvider {

    private static final int INVENTORY_SIZE = 36;

    private final Map<UUID, BodyState> bodies = new LinkedHashMap<>();
    private final Map<UUID, Integer> activeSlots = new LinkedHashMap<>();

    /** The body's active main slot, or -1 when nothing is equipped. */
    public int activeSlot(String bodyId) {
        UUID id = parse(bodyId);
        if (id == null) {
            return -1;
        }
        return activeSlots.getOrDefault(id, -1);
    }

    /** Sets the active main slot; {@code slot=-1} clears it. Returns false when the body is not live. */
    public boolean setActiveSlot(String bodyId, int slot) {
        UUID id = parse(bodyId);
        if (id == null || !bodies.containsKey(id) || slot < -1 || slot >= INVENTORY_SIZE) {
            return false;
        }
        if (slot < 0) {
            activeSlots.remove(id);
        } else {
            activeSlots.put(id, slot);
        }
        return true;
    }

    /** Spawns a carrier for {@code owner} at the owner's position and returns the new body. */
    public AgentBody spawn(ServerPlayer owner, BodyKind kind) {
        ServerLevel level = owner.serverLevel();
        Entity entity = switch (kind) {
            case MARKER -> new Marker(EntityType.MARKER, level);
            case STAND -> {
                ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
                stand.setNoGravity(true);
                stand.setSilent(true);
                yield stand;
            }
        };
        Vec3 at = owner.position();
        entity.moveTo(at.x, at.y, at.z, owner.getYRot(), owner.getXRot());
        level.addFreshEntity(entity);
        Container inventory = kind == BodyKind.STAND ? new SimpleContainer(INVENTORY_SIZE) : null;
        BodyState state = new BodyState(entity.getUUID(), kind, inventory, owner.getUUID(),
                owner.hasPermissions(2), entity);
        bodies.put(state.id(), state);
        return view(state);
    }

    /** Removes the carrier from the world; returns false when the id is not a live body. */
    public boolean despawn(String bodyId) {
        BodyState state = state(bodyId).orElse(null);
        if (state == null) {
            return false;
        }
        state.entity().discard();
        bodies.remove(state.id());
        activeSlots.remove(state.id());
        return true;
    }

    @Override
    public List<AgentBody> all() {
        List<AgentBody> views = new ArrayList<>();
        for (BodyState state : bodies.values()) {
            if (!state.entity().isRemoved()) {
                views.add(view(state));
            }
        }
        return List.copyOf(views);
    }

    @Override
    public Optional<AgentBody> resolve(String bodyId) {
        return state(bodyId).map(BodyRegistry::view);
    }

    /** The carrier behind a body id, for the ports that need its level, position or inventory. */
    public Optional<BodyState> state(String bodyId) {
        UUID id = parse(bodyId);
        if (id == null) {
            return Optional.empty();
        }
        BodyState state = bodies.get(id);
        if (state == null || state.entity().isRemoved()) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    private static UUID parse(String bodyId) {
        if (bodyId == null || bodyId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(bodyId.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static AgentBody view(BodyState state) {
        return new AgentBody() {
            @Override
            public String id() {
                return state.id().toString();
            }

            @Override
            public boolean operator() {
                return state.operator();
            }

            @Override
            public ActorPosition position() {
                Vec3 at = state.position();
                return new ActorPosition(state.level().dimension().location().toString(), at.x, at.y, at.z);
            }

            @Override
            public Set<BodyCapability> capabilities() {
                return state.kind().capabilities();
            }
        };
    }

    /** A carrier plus the state the ports act on; the entity is live, so position and level are read through it. */
    public record BodyState(UUID id, BodyKind kind, Container inventory, UUID owner, boolean operator, Entity entity) {

        public ServerLevel level() {
            return (ServerLevel) entity.level();
        }

        public Vec3 position() {
            return entity.position();
        }
    }
}