package com.rheinmetal.tianshu.client.ir;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.function.ir.core.IntentKeywordLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;

import java.io.InputStream;

public final class NamedObjectReloadListener implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation KEYWORDS_ID = ResourceLocation.fromNamespaceAndPath("tianshu", "ir-intent-keywords.json");

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        reloadKeywords(resourceManager);
        ClientNamedObjectIndexManager.rebuildIndex("client resource reload");
    }

    private void reloadKeywords(ResourceManager resourceManager) {
        try {
            InputStream is = resourceManager.getResource(KEYWORDS_ID)
                .map(res -> {
                    try {
                        return res.open();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
            if (is != null) {
                try (is) {
                    IntentKeywordLoader.reload(is);
                    LOGGER.info("IR 意图关键词从资源管理器加载完成");
                }
            } else {
                LOGGER.warn("IR 意图关键词资源未找到: {}", KEYWORDS_ID);
            }
        } catch (Exception e) {
            LOGGER.error("IR 意图关键词加载失败", e);
        }
    }
}
