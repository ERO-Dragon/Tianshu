package com.rheinmetal.tianshu.mc.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.game.LootQuery;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.game.LootQueryResult;
import com.rheinmetal.tianshu.client.tool.LootTableJsonReader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The 1.21.1 loot source: reads the datapack JSON of the block's or entity's loot table.
 *
 * <p>The game's loot table object does not expose its entries, so the datapack resource is the honest source; the
 * server passes the resource manager in when it starts, and every read happens on the server thread.</p>
 */
public final class LootQuerySource implements LootQueryPort {

    private final MinecraftServer server;

    public LootQuerySource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public java.util.concurrent.CompletableFuture<LootQueryResult> query(LootQuery query) {
        java.util.concurrent.CompletableFuture<LootQueryResult> result = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(lookup(query));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private LootQueryResult lookup(LootQuery query) {
        ResourceKey<LootTable> tableKey = tableOf(query);
        if (tableKey == null) {
            return new LootQueryResult(List.of());
        }
        JsonElement table = read(tableKey.location());
        if (table == null) {
            return new LootQueryResult(List.of());
        }
        Set<String> items = LootTableJsonReader.itemIds(table, id -> {
            ResourceLocation location = ResourceLocation.tryParse(id);
            return location == null ? null : read(location);
        });
        return new LootQueryResult(List.copyOf(items));
    }

    private ResourceKey<LootTable> tableOf(LootQuery query) {
        if (query.blockId() != null) {
            ResourceLocation id = ResourceLocation.tryParse(query.blockId());
            return id == null ? null
                    : BuiltInRegistries.BLOCK.get(id).flatMap(holder -> holder.value().getLootTable()).orElse(null);
        }
        ResourceLocation id = ResourceLocation.tryParse(query.entityId());
        return id == null ? null
                : BuiltInRegistries.ENTITY_TYPE.get(id).flatMap(holder -> holder.value().getDefaultLootTable())
                        .orElse(null);
    }

    private JsonElement read(ResourceLocation tableId) {
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(tableId.getNamespace(),
                "loot_table/" + tableId.getPath() + ".json");
        Optional<Resource> resource = server.getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream input = resource.get().open()) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }
}