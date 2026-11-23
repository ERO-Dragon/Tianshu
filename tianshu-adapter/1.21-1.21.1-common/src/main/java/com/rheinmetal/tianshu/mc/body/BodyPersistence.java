package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.EntityAccess;
import com.rheinmetal.tianshu.api.entity.EntitySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves the body registry across restarts: kind, owner, active slot and the 36 main slots, keyed by entity id.
 *
 * <p>The carrier entity itself already belongs to the world, so a restart keeps it; this file only restores the
 * registry entry and the inventory the platform owns. A body whose entity cannot be found (unloaded chunk, removed
 * by an operator) is skipped instead of being recreated at a guessed position.</p>
 */
public final class BodyPersistence {

    private static final Logger LOGGER = LoggerFactory.getLogger("tianshu/bodies");
    private static final String FILE = "tianshu_bodies.nbt";
    private static final int VERSION = 1;
    private static final int SAVE_INTERVAL_TICKS = 6000;
    private static final int MAIN_SLOTS = 36;

    private static int ticks;
    /** Set when the file could not be read: until a read succeeds, this process must not overwrite it. */
    private static boolean loadFailed;

    private BodyPersistence() {
    }

    /** Called once per server tick; writes every five minutes so a crash loses at most that window. */
    public static void tick(MinecraftServer server, BodyRegistry bodies) {
        if (++ticks >= SAVE_INTERVAL_TICKS) {
            ticks = 0;
            save(server, bodies);
        }
    }

    public static void save(MinecraftServer server, BodyRegistry bodies) {
        if (loadFailed) {
            // A read failure means the registry does not know what the file held; writing now would replace a good
            // snapshot with a partial one. The file stays as it is until a read succeeds.
            LOGGER.warn("skipping the body save: the previous read failed, so this registry cannot be trusted as the full set");
            return;
        }
        CompoundTag root = new CompoundTag();
        root.putInt("version", VERSION);
        ListTag saved = new ListTag();
        for (BodyRegistry.BodyState state : bodies.states()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("id", state.id());
            tag.putString("kind", state.kind().token());
            tag.putUUID("owner", state.owner());
            tag.putBoolean("operator", state.operator());
            // Where the body is, not only which entity carries it: an unloaded carrier still has a home.
            tag.putString("dimension", state.dimensionId());
            tag.putDouble("x", state.position().x);
            tag.putDouble("y", state.position().y);
            tag.putDouble("z", state.position().z);
            tag.putInt("activeSlot", bodies.activeSlot(state.id().toString()));
            tag.putString("access", bodies.access(state.id().toString()).name());
            ListTag trusted = new ListTag();
            for (UUID player : bodies.trusted(state.id().toString())) {
                trusted.add(StringTag.valueOf(player.toString()));
            }
            tag.put("trusted", trusted);
            ListTag equipment = new ListTag();
            for (EntitySlot slot : EntitySlot.values()) {
                ItemStack stack = bodies.equipment(state.id().toString(), slot);
                if (stack.isEmpty()) {
                    continue;
                }
                CompoundTag item = new CompoundTag();
                item.putString("slot", slot.name());
                item.put("item", stack.save(server.registryAccess()));
                equipment.add(item);
            }
            tag.put("equipment", equipment);
            ListTag inventory = new ListTag();
            if (state.inventory() != null) {
                for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                    ItemStack stack = state.inventory().getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    CompoundTag item = new CompoundTag();
                    item.putInt("slot", slot);
                    item.put("item", stack.save(server.registryAccess()));
                    inventory.add(item);
                }
            }
            tag.put("inventory", inventory);
            saved.add(tag);
        }
        root.put("bodies", saved);
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        Path temporary = path.resolveSibling(FILE + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            // Write beside the real file, then swap: a crash mid-write must never leave a half file where a good one was.
            NbtIo.writeCompressed(root, temporary);
            replaceAtomically(temporary, path);
        } catch (IOException failure) {
            LOGGER.warn("could not save agent bodies", failure);
        }
    }

    private static void replaceAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void load(MinecraftServer server, BodyRegistry bodies) {
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        if (!Files.exists(path)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag saved = root.getList("bodies", Tag.TAG_COMPOUND);
            int restored = 0;
            for (int index = 0; index < saved.size(); index++) {
                CompoundTag tag = saved.getCompound(index);
                if (restoreOne(server, bodies, tag)) {
                    restored++;
                }
            }
            loadFailed = false;
            LOGGER.info("restored {} of {} agent bodies", restored, saved.size());
        } catch (IOException | RuntimeException failure) {
            // Remembered, not just logged: a save on top of an unreadable file would replace whatever the file still
            // held with the partial set this process happened to see.
            loadFailed = true;
            LOGGER.warn("could not read agent bodies; saving is disabled until the file can be read", failure);
        }
    }

    private static boolean restoreOne(MinecraftServer server, BodyRegistry bodies, CompoundTag tag) {
        if (!tag.contains("id") || !tag.contains("kind") || !tag.contains("owner")) {
            return false;
        }
        UUID id = tag.getUUID("id");
        Optional<BodyKind> kind = BodyKind.parse(tag.getString("kind"));
        if (kind.isEmpty()) {
            return false;
        }
        Entity entity = find(server, id);
        if (entity != null && !matches(kind.get(), entity)) {
            return false;
        }
        Container inventory = kind.get() == BodyKind.MARKER ? null : new SimpleContainer(MAIN_SLOTS);
        ListTag items = tag.getList("inventory", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size() && inventory != null; index++) {
            CompoundTag item = items.getCompound(index);
            int slot = item.getInt("slot");
            if (slot < 0 || slot >= MAIN_SLOTS || !item.contains("item")) {
                continue;
            }
            Optional<ItemStack> stack = ItemStack.parse(server.registryAccess(), item.get("item"));
            stack.ifPresent(value -> inventory.setItem(slot, value));
        }
        String dimension = tag.contains("dimension")
                ? tag.getString("dimension")
                : (entity == null ? "" : entity.level().dimension().location().toString());
        net.minecraft.world.phys.Vec3 position = tag.contains("x")
                ? new net.minecraft.world.phys.Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"))
                : (entity == null ? net.minecraft.world.phys.Vec3.ZERO : entity.position());
        bodies.restore(bodies.restored(id, kind.get(), inventory, tag.getUUID("owner"),
                tag.getBoolean("operator"), entity, dimension, position), tag.getInt("activeSlot"));
        ListTag equipment = tag.getList("equipment", Tag.TAG_COMPOUND);
        for (int index = 0; index < equipment.size(); index++) {
            CompoundTag item = equipment.getCompound(index);
            EntitySlot slot = null;
            for (EntitySlot candidate : EntitySlot.values()) {
                if (candidate.name().equals(item.getString("slot"))) {
                    slot = candidate;
                }
            }
            if (slot == null || !item.contains("item")) {
                continue;
            }
            Optional<ItemStack> stack = ItemStack.parse(server.registryAccess(), item.get("item"));
            if (stack.isPresent()) {
                bodies.setEquipment(id.toString(), slot, stack.get());
            }
        }
        BodyRegistry.BodyState restored = bodies.state(id.toString()).orElse(null);
        if (restored != null) {
            bodies.applyEquipment(restored);
        }
        EntityAccess access = parseAccess(tag.getString("access"));
        bodies.setAccess(id.toString(), access);
        ListTag trusted = tag.getList("trusted", Tag.TAG_STRING);
        for (int index = 0; index < trusted.size(); index++) {
            try {
                bodies.trust(id.toString(), UUID.fromString(trusted.getString(index)));
            } catch (IllegalArgumentException notAUuid) {
                LOGGER.warn("skipping malformed trusted player id: {}", trusted.getString(index));
            }
        }
        return true;
    }

    private static EntityAccess parseAccess(String name) {
        for (EntityAccess access : EntityAccess.values()) {
            if (access.name().equalsIgnoreCase(name)) {
                return access;
            }
        }
        return EntityAccess.OWNER;
    }

    private static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean matches(BodyKind kind, Entity entity) {
        return switch (kind) {
            case MARKER -> entity instanceof Marker;
            case STAND -> entity instanceof ArmorStand;
            case WALKER -> entity instanceof WalkerBody;
        };
    }
}