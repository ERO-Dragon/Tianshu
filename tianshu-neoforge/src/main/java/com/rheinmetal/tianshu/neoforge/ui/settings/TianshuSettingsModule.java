package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.settings.protocol.SettingsEventPublisher;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsAccess;
import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsSession;
import com.rheinmetal.tianshu.neoforge.ui.settings.VanillaModuleSettingsRendererProvider;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsContext;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.settings.session.SettingsSessionRegistry;
import com.rheinmetal.tianshu.neoforge.config.TianshuBuildProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class TianshuSettingsModule {
    private final TianshuSettingsRegistrySource registrySource;
    private final VanillaModuleSettingsRendererProvider rendererProvider;
    private final GlobalDebugSettingsAccess globalDebugSettings;

    public TianshuSettingsModule(TianshuSettingsRegistrySource registrySource, GlobalDebugSettingsAccess globalDebugSettings) {
        this.registrySource = registrySource == null ? (registry, context) -> {} : registrySource;
        this.rendererProvider = new VanillaModuleSettingsRendererProvider();
        this.globalDebugSettings = java.util.Objects.requireNonNull(globalDebugSettings, "globalDebugSettings");
    }

    public Screen createScreen() {
        SettingsSessionRegistry sessions = new SettingsSessionRegistry();
        SettingsCoordinator coordinator = new SettingsCoordinator(sessions, SettingsEventPublisher.NOOP);
        GlobalDebugSettingsSession debugSession = TianshuBuildProfile.debugBuild()
                ? new GlobalDebugSettingsSession(globalDebugSettings)
                : null;
        if (debugSession != null) {
            sessions.register(debugSession);
        }
        ModuleSettingsContext context = new TianshuSettingsContext(coordinator);
        return TianshuSettingsScreen.create(context, registrySource, rendererProvider, debugSession);
    }

    public void openScreen() {
        Minecraft.getInstance().setScreen(createScreen());
    }

}
