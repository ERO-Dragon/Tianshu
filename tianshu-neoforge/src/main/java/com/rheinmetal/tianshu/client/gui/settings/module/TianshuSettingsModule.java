package com.rheinmetal.tianshu.client.gui.settings.module;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.protocol.SettingsEventPublisher;
import com.rheinmetal.tianshu.client.gui.settings.registry.BuiltinSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.ModuleSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.render.VanillaModuleSettingsRendererProvider;
import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSessionRegistry;
import com.rheinmetal.tianshu.core.TianshuCoreManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class TianshuSettingsModule {
    private final TianshuCoreManager coreManager;
    private final SettingsCoordinator coordinator;
    private final TianshuSettingsRegistrySource registrySource;
    private final VanillaModuleSettingsRendererProvider rendererProvider;

    public TianshuSettingsModule(TianshuCoreManager coreManager) {
        this(coreManager, false);
    }

    public TianshuSettingsModule(TianshuCoreManager coreManager, boolean includeBuiltinExamples) {
        this(coreManager, registrySource(coreManager, includeBuiltinExamples));
    }

    public TianshuSettingsModule(TianshuCoreManager coreManager, TianshuSettingsRegistrySource registrySource) {
        this.coreManager = coreManager;
        this.coordinator = new SettingsCoordinator(new SettingsSessionRegistry(), SettingsEventPublisher.NOOP);
        this.registrySource = registrySource == null ? (registry, context) -> {} : registrySource;
        this.rendererProvider = new VanillaModuleSettingsRendererProvider();
    }

    public SettingsCoordinator coordinator() {
        return coordinator;
    }

    public Screen createScreen() {
        if (coreManager != null) {
            coreManager.initWorkers();
        }
        ModuleSettingsContext context = new TianshuSettingsContext(coordinator);
        return TianshuSettingsScreen.create(context, registrySource, rendererProvider);
    }

    public void openScreen() {
        Minecraft.getInstance().setScreen(createScreen());
    }

    private static TianshuSettingsRegistrySource registrySource(TianshuCoreManager coreManager, boolean includeBuiltinExamples) {
        TianshuSettingsRegistrySource moduleSource = coreManager == null ? (registry, context) -> {} : new ModuleSettingsRegistrySource(coreManager::managedModules);
        return includeBuiltinExamples ? CompositeSettingsRegistrySource.of(moduleSource, new BuiltinSettingsRegistrySource()) : moduleSource;
    }
}
