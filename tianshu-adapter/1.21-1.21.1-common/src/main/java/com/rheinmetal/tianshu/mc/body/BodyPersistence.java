package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.BodyAccess;
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
        CompoundTag root = new CompoundTag();
        root.putInt("version", VERSION);
        ListTag saved = new ListTag();
        for (BodyRegistry.BodyState state : bodies.states()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("id", state.id());
            tag.putString("kind", state.kind().token());
            tag.putUUID("owner", state.owner());
            tag.putBoolean("operator", state.operator());
            tag.putInt("activeSlot", bodies.activeSlot(state.id().toString()));
            tag.putString("access", bodies.access(state.id().toString()).name());
            ListTag trusted = new ListTag();
            for (UUID player : bodies.trusted(state.id().toString())) {
                trusted.add(StringTag.valueOf(player.toString()));
            }
            tag.put("trusted", trusted);
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
        try {
            Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(root, path);
        } catch (IOException failure) {
            LOGGER.warn("could not save agent bodies", failure);
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
            LOGGER.info("restored {} of {} agent bodies", restored, saved.size());
        } catch (IOException failure) {
            LOGGER.warn("could not read agent bodies", failure);
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
        if (entity == null || !matches(kind.get(), entity)) {
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
        bodies.restore(new BodyRegistry.BodyState(id, kind.get(), inventory, tag.getUUID("owner"),
                tag.getBoolean("operator"), entity), tag.getInt("activeSlot"));
        BodyAccess access = parseAccess(tag.getString("access"));
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

    private static BodyAccess parseAccess(String name) {
        for (BodyAccess access : BodyAccess.values()) {
            if (access.name().equalsIgnoreCase(name)) {
                return access;
            }
        }
        return BodyAccess.OWNER;
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