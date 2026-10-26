package com.rheinmetal.tianshu.neoforge;

import com.rheinmetal.tianshu.api.assembly.ClientKeyBindings;
import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.KeyBindings;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.neoforge.ui.NeoForgeClientKeyBindings;
import com.rheinmetal.tianshu.neoforge.bootstrap.NeoForgeClientBootstrap;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

@Mod(TianshuNeoForge.MOD_ID)
public final class TianshuNeoForge {
    public static final String MOD_ID = "tianshu";
    private final NeoForgeClientBootstrap clientBootstrap = new NeoForgeClientBootstrap();

    public TianshuNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        // Modules are platform-free: when they integrate with an optional partner, the only sane answer to
        // "is it there?" has to come from here. Registered before any module assembles.
        TianshuRuntimePorts.register(InstalledMods.class,
                modId -> modId != null && !modId.isBlank() && ModList.get().isLoaded(modId));
        // A module's settings row may want to send the player to the controls screen; the binding is a
        // Minecraft object, so opening that screen is a platform job offered through a port.
        TianshuRuntimePorts.register(ClientKeyBindings.class, new NeoForgeClientKeyBindings());
        // A module that wants a key declares it here while its jar loads; the platform registers it in the
        // key-mapping event later, so the module never touches a KeyMapping.
        TianshuRuntimePorts.register(KeyBindings.class, clientBootstrap.keyBindings());
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modEventBus.addListener(clientBootstrap::registerKeyMappings);
        modEventBus.addListener(clientBootstrap::registerReloadListeners);
        modEventBus.addListener(clientBootstrap::registerShaders);
        modEventBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.addListener(this::gameShuttingDown);
    }

    private void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(clientBootstrap::start);
    }

    private void gameShuttingDown(GameShuttingDownEvent event) {
        clientBootstrap.shutdown();
    }
}
