package com.rheinmetal.tianshu.neoforge;

import com.rheinmetal.tianshu.api.assembly.ClientKeyBindings;
import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.KeyBindings;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.neoforge.bootstrap.NeoForgeClientBootstrap;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeBossEventWatch;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientFactWatch;
import com.rheinmetal.tianshu.neoforge.network.TianshuNetwork;
import com.rheinmetal.tianshu.neoforge.server.PresenceServerCollector;
import com.rheinmetal.tianshu.neoforge.ui.NeoForgeClientKeyBindings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * The mod entry point, loaded on both sides.
 *
 * <p>Tianshu is a client product with an optional server component: the kernel, the modules and the assistant all
 * run on the client, and a dedicated server only contributes authoritative facts. This class therefore builds the
 * client subsystem <em>only</em> when the client side is present — a server must be able to load this jar without
 * touching a single client class.</p>
 */
@Mod(TianshuNeoForge.MOD_ID)
public final class TianshuNeoForge {
    public static final String MOD_ID = "tianshu";

    /** {@code null} on a dedicated server: every client subsystem is constructed only on the client. */
    private final NeoForgeClientBootstrap clientBootstrap;

    /** {@code null} on a client: the server component only exists where authoritative server events exist. */
    private final PresenceServerCollector serverCollector;

    public TianshuNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        // Modules are platform-free: when they integrate with an optional partner, the only sane answer to
        // "is it there?" has to come from here. Registered before any module assembles, on both sides.
        TianshuRuntimePorts.register(InstalledMods.class,
                modId -> modId != null && !modId.isBlank() && ModList.get().isLoaded(modId));
        // The one network channel is registered on both sides: whoever hosts the server component still has to know
        // how a fact is shaped, and a client has to know how to receive it.
        modEventBus.addListener(TianshuNetwork::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            clientBootstrap = new NeoForgeClientBootstrap();
            serverCollector = null;
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
            // A boss bar is the player's own signal that a fight started; the client is where it is visible.
            NeoForge.EVENT_BUS.addListener(NeoForgeBossEventWatch::onBossEventProgress);
            // Facts the client observes exactly on its own: level rebuilds, respawn, opening a container.
            new NeoForgeClientFactWatch().register(NeoForge.EVENT_BUS);
        } else {
            clientBootstrap = null;
            // The optional server component: collect authoritative facts for the acting player, nothing else.
            serverCollector = new PresenceServerCollector();
            serverCollector.register(NeoForge.EVENT_BUS);
        }
        NeoForge.EVENT_BUS.addListener(this::gameShuttingDown);
    }

    private void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(clientBootstrap::start);
    }

    private void gameShuttingDown(GameShuttingDownEvent event) {
        if (clientBootstrap != null) {
            clientBootstrap.shutdown();
        }
    }
}