package com.rheinmetal.tianshu.client.ir;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

public final class ItemCommandReloadListener implements ResourceManagerReloadListener {
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        ClientItemCommandManager.rebuildIndex("client resource reload");
    }
}
