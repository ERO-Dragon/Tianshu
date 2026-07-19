package com.rheinmetal.tianshu.neoforge.event;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.function.ir.core.IntentKeywordLoader;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.ByteArrayInputStream;

public final class NamedObjectReloadListener extends SimplePreparableReloadListener<byte[]> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation KEYWORDS_ID = ResourceLocation.fromNamespaceAndPath("tianshu", "ir-intent-keywords.json");
    private final ClientNamedObjectIndexManager indexManager;
    private final Runnable refreshSnapshot;

    public NamedObjectReloadListener(ClientNamedObjectIndexManager indexManager, Runnable refreshSnapshot) {
        this.indexManager = indexManager;
        this.refreshSnapshot = refreshSnapshot == null ? () -> { } : refreshSnapshot;
    }

    @Override
    protected byte[] prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return readKeywords(resourceManager);
    }

    @Override
    protected void apply(byte[] keywords, ResourceManager resourceManager, ProfilerFiller profiler) {
        refreshSnapshot.run();
        indexManager.reloadAsync(
                "client resource reload",
                () -> reloadKeywords(keywords)
        );
    }

    private byte[] readKeywords(ResourceManager resourceManager) {
        try {
            InputStream input = resourceManager.getResource(KEYWORDS_ID)
                .map(res -> {
                    try {
                        return res.open();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
            if (input == null) {
                LOGGER.warn("IR 意图关键词资源未找到: {}", KEYWORDS_ID);
                return null;
            }
            try (input) {
                return input.readAllBytes();
            }
        } catch (Exception e) {
            LOGGER.error("IR 意图关键词资源读取失败", e);
            return null;
        }
    }

    private void reloadKeywords(byte[] keywords) {
        if (keywords == null || keywords.length == 0) {
            return;
        }
        try (InputStream input = new ByteArrayInputStream(keywords)) {
            IntentKeywordLoader.reload(input);
            LOGGER.info("IR 意图关键词从资源管理器加载完成");
        } catch (Exception e) {
            LOGGER.error("IR 意图关键词加载失败", e);
        }
    }
}
