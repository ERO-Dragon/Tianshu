package com.rheinmetal.tianshu.client.junk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.function.junk.JunkItemIdPolicy;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JunkListStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH = FMLPaths.CONFIGDIR.get()
            .resolve("TianshuAIAssistant")
            .resolve("module")
            .resolve("junk")
            .resolve("cache")
            .resolve("junk-list.json");

    private final Set<String> itemIds = new LinkedHashSet<>();
    private boolean loaded;

    public synchronized boolean add(String itemId) {
        ensureLoaded();
        if (!JunkItemIdPolicy.canPersistAsJunk(itemId)) return false;
        boolean changed = itemIds.add(itemId);
        if (changed) save();
        return changed;
    }

    public synchronized boolean remove(String itemId) {
        ensureLoaded();
        if (!JunkItemIdPolicy.isValidItemId(itemId)) return false;
        boolean changed = itemIds.remove(itemId);
        if (changed) save();
        return changed;
    }

    public synchronized boolean contains(String itemId) {
        ensureLoaded();
        return itemIds.contains(itemId);
    }

    public synchronized Set<String> snapshot() {
        ensureLoaded();
        return Collections.unmodifiableSet(new LinkedHashSet<>(itemIds));
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(STORE_PATH)) return;
        try (Reader reader = Files.newBufferedReader(STORE_PATH)) {
            StoreFile file = GSON.fromJson(reader, StoreFile.class);
            if (file == null || file.items == null) return;
            for (String itemId : file.items) {
                if (JunkItemIdPolicy.canPersistAsJunk(itemId)) {
                    itemIds.add(itemId);
                }
            }
        } catch (IOException | JsonSyntaxException exception) {
            LOGGER.warn("净囊清单读取失败，将使用空清单: {}", exception.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(STORE_PATH)) {
                GSON.toJson(new StoreFile(new ArrayList<>(itemIds)), writer);
            }
        } catch (IOException exception) {
            LOGGER.warn("净囊清单写入失败: {}", exception.getMessage());
        }
    }

    private record StoreFile(List<String> items) {
    }
}
