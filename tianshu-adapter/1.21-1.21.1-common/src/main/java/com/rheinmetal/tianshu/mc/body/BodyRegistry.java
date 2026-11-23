package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityProvider;
import com.rheinmetal.tianshu.api.entity.EntityAccess;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntitySlot;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The server-side agent bodies of this adapter. The registry owns the carriers and their inventories; the platform
 * only sees {@link EntityActor} snapshots through {@link #resolve(String)}.
 *
 * <p>Bodies and their inventories are saved by {@link BodyPersistence}; without a running server they are memory
 * state only. A removed entity makes its body disappear rather than turning into a still body.</p>
 */
public final class BodyRegistry implements EntityProvider {

    private static final int INVENTORY_SIZE = 36;

    private final Map<UUID, BodyState> bodies = new LinkedHashMap<>();
    private final Map<UUID, Integer> activeSlots = new LinkedHashMap<>();
    private final Map<UUID, EntityAccess> accessPolicies = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> trusted = new LinkedHashMap<>();
    private final Map<UUID, EnumMap<EntitySlot, ItemStack>> equipment = new LinkedHashMap<>();
    private final MinecraftServer server;
    private final ActionPlayerFactory actionPlayers;
    private final PlayerBodyFactory playerBodies;
    private EntityActions actions;

    public BodyRegistry(MinecraftServer server, ActionPlayerFactory actionPlayers, PlayerBodyFactory playerBodies) {
        this.server = server;
        this.actionPlayers = actionPlayers;
        this.playerBodies = playerBodies;
    }

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

    /** The access policy of a body; a live body defaults to owner-only. */
    public EntityAccess access(String bodyId) {
        UUID id = parse(bodyId);
        if (id == null) {
            return EntityAccess.OWNER;
        }
        return accessPolicies.getOrDefault(id, EntityAccess.OWNER);
    }

    /** Sets the policy; owner is the default, so it is stored as "no entry". Returns false for an unknown body. */
    public boolean setAccess(String bodyId, EntityAccess access) {
        UUID id = parse(bodyId);
        if (id == null || !bodies.containsKey(id) || access == null) {
            return false;
        }
        if (access == EntityAccess.OWNER) {
            accessPolicies.remove(id);
        } else {
            accessPolicies.put(id, access);
        }
        return true;
    }

    /** The players the owner trusted for this body. */
    public Set<UUID> trusted(String bodyId) {
        UUID id = parse(bodyId);
        if (id == null) {
            return Set.of();
        }
        return Set.copyOf(trusted.getOrDefault(id, Set.of()));
    }

    public boolean trust(String bodyId, UUID player) {
        UUID id = parse(bodyId);
        if (id == null || !bodies.containsKey(id) || player == null) {
            return false;
        }
        trusted.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(player);
        return true;
    }

    public boolean untrust(String bodyId, UUID player) {
        UUID id = parse(bodyId);
        if (id == null || player == null) {
            return false;
        }
        Set<UUID> players = trusted.get(id);
        if (players == null) {
            return true;
        }
        players.remove(player);
        if (players.isEmpty()) {
            trusted.remove(id);
        }
        return true;
    }

    /**
     * Player-driven calls must pass the body's policy; the caller runtime and the server are trusted by
     * construction, because they carry the operator permission that gates write tools in the first place.
     */
    @Override
    public Optional<ToolException> checkAccess(ToolCaller caller, String bodyId) {
        if (caller == null || caller.kind() != ToolCaller.Kind.PLAYER) {
            return Optional.empty();
        }
        UUID id = parse(bodyId);
        if (id == null || !bodies.containsKey(id)) {
            return Optional.empty();
        }
        UUID callerId = parse(caller.id());
        if (callerId != null && callerId.equals(bodies.get(id).owner())) {
            return Optional.empty();
        }
        EntityAccess policy = accessPolicies.getOrDefault(id, EntityAccess.OWNER);
        if (policy == EntityAccess.OPEN) {
            return Optional.empty();
        }
        if (policy == EntityAccess.TRUSTED && callerId != null
                && trusted.getOrDefault(id, Set.of()).contains(callerId)) {
            return Optional.empty();
        }
        return Optional.of(new ToolException(ToolError.NO_PERMISSION,
                "you are not allowed to command this body"));
    }

    /** Spawns a carrier for {@code owner} at the owner's position and returns the new body. */
    public EntityActor spawn(ServerPlayer owner, BodyKind kind) {
        return spawn(owner, kind, false);
    }

    /**
     * Spawns a body, optionally player-backed.
     *
     * <p>The identity is decided here, once: a player-backed body acts through its own long-lived player, an ordinary
     * body borrows a facade per call. Nothing downstream branches on it.</p>
     */
    public EntityActor spawn(ServerPlayer owner, BodyKind kind, boolean playerIdentity) {
        ServerLevel level = owner.serverLevel();
        Entity entity;
        BodyIdentity identity;
        if (playerIdentity) {
            ServerPlayer player = playerBodies == null ? null : playerBodies.create(level, owner.getUUID());
            if (player == null) {
                throw new IllegalStateException("this adapter cannot create player-backed bodies");
            }
            entity = player;
            identity = BodyIdentity.playerBacked(player);
        } else {
            entity = switch (kind) {
                case MARKER -> new Marker(EntityType.MARKER, level);
                case STAND -> {
                    ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
                    stand.setNoGravity(true);
                    stand.setSilent(true);
                    yield stand;
                }
                case WALKER -> {
                    WalkerBody walker = BodyCarriers.walker().create(level);
                    if (walker == null) {
                        throw new IllegalStateException("cannot create a walker body");
                    }
                    yield walker;
                }
            };
            identity = BodyIdentity.borrowed(actionPlayers);
        }
        Vec3 at = owner.position();
        entity.moveTo(at.x, at.y, at.z, owner.getYRot(), owner.getXRot());
        level.addFreshEntity(entity);
        Container inventory = kind == BodyKind.MARKER ? null : new SimpleContainer(INVENTORY_SIZE);
        BodyState state = new BodyState(entity.getUUID(), kind, inventory, owner.getUUID(),
                owner.hasPermissions(2), entity, identity, level.dimension().location().toString(), entity.position());
        bodies.put(state.id(), state);
        return view(state);
    }

    /** Removes the carrier from the world; returns false when the id is not a live body. */
    public boolean despawn(String bodyId) {
        UUID id = parse(bodyId);
        BodyState state = id == null ? null : bodies.get(id);
        if (state == null) {
            return false;
        }
        if (state.loaded()) {
            state.entity().discard();
        }
        bodies.remove(state.id());
        activeSlots.remove(state.id());
        accessPolicies.remove(state.id());
        trusted.remove(state.id());
        equipment.remove(state.id());
        return true;
    }

    @Override
    public List<EntityActor> all() {
        List<EntityActor> views = new ArrayList<>();
        for (BodyState state : bodies.values()) {
            if (state.loaded()) {
                views.add(view(state));
            }
        }
        return List.copyOf(views);
    }

    /** Every body's full state, loaded or not; the persistence layer reads inventories from here. */
    public List<BodyState> states() {
        return List.copyOf(bodies.values());
    }

    /**
     * Reconciles the records with the world once per server tick.
     *
     * <p>Two transitions matter and neither has a reliable event across loaders: a carrier whose chunk unloads keeps
     * its record but must not keep the entity alive, and a carrier whose chunk comes back has to find its record again.
     * Clearing and rebinding here is what makes a body survive a walk out of the loaded area without losing its owner,
     * inventory or equipment.</p>
     */
    public void refreshCarriers(MinecraftServer server) {
        for (BodyState state : List.copyOf(bodies.values())) {
            if (state.loaded()) {
                Entity carrier = state.entity();
                if (carrier == null || carrier.level().isClientSide()) {
                    state.detach();
                }
                continue;
            }
            state.detach();
            Entity found = findCarrier(server, state);
            if (found != null) {
                state.attach(found);
                applyEquipment(state);
            }
        }
    }

    /** The carrier for an unloaded record: same level, same uuid, still the shape the record says. */
    private static Entity findCarrier(MinecraftServer server, BodyState state) {
        if (server == null || state.dimensionId().isBlank()) {
            return null;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.resources.ResourceLocation.tryParse(state.dimensionId());
        if (key == null) {
            return null;
        }
        ServerLevel level = server.getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, key));
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(state.id());
        return found != null && !found.isRemoved() ? found : null;
    }

    /** Puts a body back after a restart; its entity already exists in the world. */
    public void restore(BodyState state, int activeSlot) {
        bodies.put(state.id(), state);
        if (activeSlot >= 0) {
            activeSlots.put(state.id(), activeSlot);
        }
        applyEquipment(state);
    }

    /** The item equipped on one body slot; empty when nothing is equipped there. */
    public ItemStack equipment(String bodyId, EntitySlot slot) {
        UUID id = parse(bodyId);
        if (id == null || slot == null) {
            return ItemStack.EMPTY;
        }
        EnumMap<EntitySlot, ItemStack> slots = equipment.get(id);
        return slots == null ? ItemStack.EMPTY : slots.getOrDefault(slot, ItemStack.EMPTY);
    }

    /** Replaces one equipment slot; an empty stack clears it. */
    public void setEquipment(String bodyId, EntitySlot slot, ItemStack stack) {
        UUID id = parse(bodyId);
        if (id == null || slot == null || !bodies.containsKey(id)) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            EnumMap<EntitySlot, ItemStack> slots = equipment.get(id);
            if (slots != null) {
                slots.remove(slot);
                if (slots.isEmpty()) {
                    equipment.remove(id);
                }
            }
            return;
        }
        equipment.computeIfAbsent(id, ignored -> new EnumMap<>(EntitySlot.class)).put(slot, stack.copy());
    }

    /** Pushes every equipment slot onto the carrier entity, so vanilla armour and offhand rules apply. */
    public void applyEquipment(BodyState state) {
        if (!(state.entity() instanceof LivingEntity living)) {
            return;
        }
        for (EntitySlot slot : EntitySlot.values()) {
            living.setItemSlot(mcSlot(slot), equipment(state.id().toString(), slot));
        }
    }

    private static EquipmentSlot mcSlot(EntitySlot slot) {
        return switch (slot) {
            case MAIN_HAND -> EquipmentSlot.MAINHAND;
            case OFF_HAND -> EquipmentSlot.OFFHAND;
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }

    /** Adds items to a body's inventory, stacking first; returns how many fit. */
    public int give(String bodyId, ItemStack stack) {
        UUID id = parse(bodyId);
        if (id == null || stack.isEmpty()) {
            return 0;
        }
        BodyState state = bodies.get(id);
        if (state == null || state.inventory() == null) {
            return 0;
        }
        Container inventory = state.inventory();
        int remaining = stack.getCount();
        for (int slot = 0; slot < INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty()) {
                int put = Math.min(remaining, stack.getMaxStackSize());
                inventory.setItem(slot, new ItemStack(stack.getItem(), put));
                remaining -= put;
            } else if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int put = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                existing.grow(put);
                remaining -= put;
            }
        }
        int added = stack.getCount() - remaining;
        if (added > 0) {
            inventory.setChanged();
        }
        return added;
    }

    /**
     * Handles a carrier death: with {@code keepInventory} the body is kept alive at full health; otherwise its
     * inventory and equipment are dropped at its position and the registry entry is removed.
     *
     * <p>Returns true when the body was kept, which tells the loader hook to cancel the death.</p>
     */
    public boolean onDeath(Entity entity, boolean keepInventory) {
        if (entity == null) {
            return false;
        }
        BodyState state = bodies.get(entity.getUUID());
        if (state == null) {
            return false;
        }
        if (keepInventory) {
            if (entity instanceof LivingEntity living) {
                living.setHealth(living.getMaxHealth());
                living.clearFire();
            }
            return true;
        }
        dropAll(state);
        bodies.remove(state.id());
        activeSlots.remove(state.id());
        accessPolicies.remove(state.id());
        trusted.remove(state.id());
        equipment.remove(state.id());
        return false;
    }

    private void dropAll(BodyState state) {
        ServerLevel level = state.level();
        Vec3 at = state.position();
        List<ItemStack> stacks = new ArrayList<>();
        if (state.inventory() != null) {
            for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
                ItemStack stack = state.inventory().getItem(slot);
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                    state.inventory().setItem(slot, ItemStack.EMPTY);
                }
            }
            state.inventory().setChanged();
        }
        if (equipment.containsKey(state.id())) {
            for (EntitySlot slot : EntitySlot.values()) {
                ItemStack stack = equipment.get(state.id()).get(slot);
                if (stack != null && !stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
                setEquipment(state.id().toString(), slot, ItemStack.EMPTY);
            }
        }
        applyEquipment(state);
        for (ItemStack stack : stacks) {
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y + 0.5D, at.z,
                    stack));
        }
    }

    @Override
    public Optional<EntityActor> resolve(String bodyId) {
        return state(bodyId).map(BodyRegistry::view);
    }

    @Override
    public Optional<EntityActions> actions(String bodyId) {
        if (state(bodyId).isEmpty()) {
            return Optional.empty();
        }
        if (actions == null) {
            actions = new CarrierActions(server, this, actionPlayers);
        }
        return Optional.of(actions);
    }

    /** The carrier behind a body id, for the ports that need its level, position or inventory. */
    public Optional<BodyState> state(String bodyId) {
        UUID id = parse(bodyId);
        if (id == null) {
            return Optional.empty();
        }
        BodyState state = bodies.get(id);
        if (state == null || !state.loaded()) {
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

    private static EntityActor view(BodyState state) {
        return new EntityActor() {
            @Override
            public String id() {
                return state.id().toString();
            }

            @Override
            public boolean operator() {
                return state.operator();
            }

            @Override
            public boolean playerLike() {
                return state.identity().playerLike();
            }

            @Override
            public ActorPosition position() {
                Vec3 at = state.position();
                return new ActorPosition(state.level().dimension().location().toString(), at.x, at.y, at.z);
            }

            @Override
            public Set<EntityCapability> capabilities() {
                return state.kind().capabilities();
            }
        };
    }

    /**
     * A body restored from disk.
     *
     * <p>Restored bodies come back with the ordinary, borrowed identity: a player-backed identity lives only as long as
     * its player, and a saved body has none. Persistence therefore stores shapes and inventories, not identities.</p>
     *
     * <p>{@code entity} may be null: a body whose carrier is in an unloaded chunk is still a body, with an owner, an
     * inventory and a set of permissions. Dropping that record because the entity was not there yet is how a body's
     * belongings disappeared on the next save.</p>
     */
    public BodyState restored(UUID id, BodyKind kind, Container inventory, UUID owner, boolean operator, Entity entity,
                              String dimensionId, Vec3 position) {
        return new BodyState(id, kind, inventory, owner, operator, entity, BodyIdentity.borrowed(actionPlayers),
                dimensionId, position);
    }

    /**
     * A body's platform data, plus its carrier while that carrier is loaded.
     *
     * <p>The data outlives the entity on purpose. Minecraft unloads an entity with its chunk and marks it removed on
     * death; neither means the body stopped existing, and the difference is exactly what the save file has to remember.
     * {@link #loaded()} is the one question a port asks before touching the carrier.</p>
     */
    public static final class BodyState {

        private final UUID id;
        private final BodyKind kind;
        private final Container inventory;
        private final UUID owner;
        private final boolean operator;
        private final BodyIdentity identity;
        private volatile Entity entity;
        private volatile String dimensionId;
        private volatile Vec3 lastPosition;

        BodyState(UUID id, BodyKind kind, Container inventory, UUID owner, boolean operator, Entity entity,
                  BodyIdentity identity, String dimensionId, Vec3 position) {
            this.id = id;
            this.kind = kind;
            this.inventory = inventory;
            this.owner = owner;
            this.operator = operator;
            this.identity = identity;
            this.dimensionId = dimensionId == null ? "" : dimensionId;
            this.lastPosition = position == null ? Vec3.ZERO : position;
            attachIfPresent(entity);
        }

        public UUID id() {
            return id;
        }

        public BodyKind kind() {
            return kind;
        }

        public Container inventory() {
            return inventory;
        }

        public UUID owner() {
            return owner;
        }

        public boolean operator() {
            return operator;
        }

        public BodyIdentity identity() {
            return identity;
        }

        /** The carrier, or null while it is unloaded. Ports must ask {@link #loaded()} before using it. */
        public Entity entity() {
            return entity;
        }

        public boolean loaded() {
            Entity current = entity;
            return current != null && !current.isRemoved();
        }

        /** Where the body is; while unloaded this is where it was when its chunk let go. */
        public Vec3 position() {
            Entity current = entity;
            return current == null ? lastPosition : current.position();
        }

        public String dimensionId() {
            Entity current = entity;
            if (current != null) {
                return current.level().dimension().location().toString();
            }
            return dimensionId;
        }

        public ServerLevel level() {
            Entity current = entity;
            if (current == null) {
                throw new IllegalStateException("body " + id + " has no loaded carrier");
            }
            return (ServerLevel) current.level();
        }

        private void attachIfPresent(Entity candidate) {
            if (candidate == null) {
                return;
            }
            this.entity = candidate;
            this.dimensionId = candidate.level().dimension().location().toString();
            this.lastPosition = candidate.position();
        }

        void attach(Entity candidate) {
            attachIfPresent(candidate);
        }

        /** Keeps where the body was, then lets go of the carrier so an unloaded chunk cannot pin it. */
        void detach() {
            Entity current = entity;
            if (current == null) {
                return;
            }
            lastPosition = current.position();
            dimensionId = current.level().dimension().location().toString();
            entity = null;
        }
    }
}