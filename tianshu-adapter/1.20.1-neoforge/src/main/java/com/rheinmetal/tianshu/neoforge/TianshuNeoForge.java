package com.rheinmetal.tianshu.neoforge;

import com.rheinmetal.tianshu.api.assembly.ClientKeyBindings;
import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.KeyBindings;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.host.ServerActionClient;
import com.rheinmetal.tianshu.mc.ui.VanillaKeyBindings;
import com.rheinmetal.tianshu.neoforge.bootstrap.NeoForgeClientBootstrap;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeBossEventWatch;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientFactWatch;
import com.rheinmetal.tianshu.neoforge.network.TianshuNetwork;
import com.rheinmetal.tianshu.neoforge.server.GameFactCollector;
import com.rheinmetal.tianshu.neoforge.server.ServerActionHandler;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * The mod entry point, loaded on both sides.
 *
 * <p>Tianshu is a client product with an optional server component: the kernel, the modules and the assistant all
 * run on the client, and a dedicated server only contributes authoritative facts. This class therefore builds the
 * client subsystem <em>only</em> when the client side is present — a server must be able to load this jar without
 * touching a single client class.</p>
 *
 * <p>Forge 47 has no mod-container constructor injection: the buses come from {@link FMLJavaModLoadingContext} and
 * the configs from {@link ModLoadingContext}, which is this generation's normal shape.</p>
 */
@Mod(TianshuNeoForge.MOD_ID)
public final class TianshuNeoForge {
    public static final String MOD_ID = "tianshu_ferry";

    /** The action channel client: one per process, shared by the network handlers and the client subsystem. */
    private static ServerActionClient actionClient;

    public static ServerActionClient actionClient() {
        return actionClient;
    }

    /** {@code null} on a dedicated server: every client subsystem is constructed only on the client. */
    private final NeoForgeClientBootstrap clientBootstrap;

    /**
     * The optional server component, present on every distribution.
     *
     * <p>A dedicated server always has it; a client has it because single player runs an integrated server in the
     * same process, and the same packet path has to feed those facts. Listeners only act on {@code ServerPlayer}, so
     * on a client connected to a remote server they simply never fire.</p>
     */
    private final GameFactCollector serverCollector;

    public TianshuNeoForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        // Modules are platform-free: when they integrate with an optional partner, the only sane answer to
        // "is it there?" has to come from here. Registered before any module assembles, on both sides.
        TianshuRuntimePorts.register(InstalledMods.class,
                modId -> modId != null && !modId.isBlank() && ModList.get().isLoaded(modId));
        // The brain: one action client per process. The network handlers deliver answers to it and the client
        // bootstrap reads its snapshots, so no static has to describe the client object.
        actionClient = new ServerActionClient(TianshuNetwork::sendToServer);
        // The one channel is registered on both sides: whoever hosts the server component still has to know how a
        // fact is shaped, and a client has to know how to receive it.
        TianshuNetwork.register();
        // Server-side action switches: independent of the client config, off by default, reloaded with the server.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerActionConfig.SPEC);
        // The collector is registered on both distributions: in single player the integrated server lives in this
        // same process, and "single player takes the same packet path" is a stated requirement. Its listeners only
        // act on {@code ServerPlayer}, so on a client connected to a remote server they simply never fire.
        serverCollector = new GameFactCollector();
        serverCollector.register(MinecraftForge.EVENT_BUS);
        // The action channel announces its enabled set on join; the client side builds its toolbar from that.
        ServerActionHandler.register(MinecraftForge.EVENT_BUS);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            clientBootstrap = new NeoForgeClientBootstrap(actionClient);
            // A module's settings row may want to send the player to the controls screen; the binding is a
            // Minecraft object, so opening that screen is a platform job offered through a port.
            TianshuRuntimePorts.register(ClientKeyBindings.class, new VanillaKeyBindings());
            // A module that wants a key declares it here while its jar loads; the platform registers it in the
            // key-mapping event later, so the module never touches a KeyMapping.
            TianshuRuntimePorts.register(KeyBindings.class, clientBootstrap.keyBindings());
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            modEventBus.addListener(clientBootstrap::registerKeyMappings);
            modEventBus.addListener(clientBootstrap::registerReloadListeners);
            modEventBus.addListener(clientBootstrap::registerShaders);
            modEventBus.addListener(this::clientSetup);
            // A boss bar is the player's own signal that a fight started; the client is where it is visible.
            NeoForgeBossEventWatch.register(MinecraftForge.EVENT_BUS);
            // Facts the client observes exactly on its own: level rebuilds and opening a container.
            new NeoForgeClientFactWatch().register(MinecraftForge.EVENT_BUS);
        } else {
            clientBootstrap = null;
        }
        MinecraftForge.EVENT_BUS.addListener(this::gameShuttingDown);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(clientBootstrap::start);
    }

    private void gameShuttingDown(GameShuttingDownEvent event) {
        if (clientBootstrap != null) {
            clientBootstrap.shutdown();
        }
    }
}
