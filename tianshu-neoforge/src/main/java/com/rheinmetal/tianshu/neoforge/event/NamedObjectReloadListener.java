package com.rheinmetal.tianshu.neoforge.event;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.function.ir.core.IntentKeywordLoader;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.function.Supplier;

public final class NamedObjectReloadListener extends SimplePreparableReloadListener<byte[]> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation KEYWORDS_ID = ResourceLocation.fromNamespaceAndPath("tianshu", "ir-intent-keywords.json");
    private final Supplier<ClientNamedObjectIndexManager> indexManagerSupplier;
    private final Runnable refreshSnapshot;

    public NamedObjectReloadListener(Supplier<ClientNamedObjectIndexManager> indexManagerSupplier, Runnable refreshSnapshot) {
        this.indexManagerSupplier = Objects.requireNonNull(indexManagerSupplier, "indexManagerSupplier");
        this.refreshSnapshot = refreshSnapshot == null ? () -> { } : refreshSnapshot;
    }

    @Override
    protected byte[] prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return readKeywords(resourceManager);
    }

    @Override
    protected void apply(byte[] keywords, ResourceManager resourceManager, ProfilerFiller profiler) {
        refreshSnapshot.run();
        ClientNamedObjectIndexManager indexManager = indexManagerSupplier.get();
        indexManager.reloadAsync(
                "client resource reload",
                () -> reloadKeywords(keywords)
        );
    }

    private byte[] readKeywords(ResourceManager resourceManager) {
        Resource resource = resourceManager.getResource(KEYWORDS_ID).orElse(null);
        if (resource == null) {
            LOGGER.warn("NEOFORGE_IR_KEYWORDS_RESOURCE_MISSING id={}", KEYWORDS_ID);
            return null;
        }
        try (InputStream input = resource.open()) {
            return input.readAllBytes();
        } catch (Exception e) {
            LOGGER.error("NEOFORGE_IR_KEYWORDS_RESOURCE_READ_FAILED id={}", KEYWORDS_ID, e);
            return null;
        }
    }

    private void reloadKeywords(byte[] keywords) {
        if (keywords == null || keywords.length == 0) {
            return;
        }
        try (InputStream input = new ByteArrayInputStream(keywords)) {
            IntentKeywordLoader.reload(input);
            LOGGER.info("NEOFORGE_IR_KEYWORDS_RELOADED");
        } catch (Exception e) {
            LOGGER.error("NEOFORGE_IR_KEYWORDS_RELOAD_FAILED", e);
        }
    }
}
