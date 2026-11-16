package com.rheinmetal.tianshu.neoforge;

import com.rheinmetal.tianshu.api.assembly.ClientKeyBindings;
import com.rheinmetal.tianshu.api.assembly.LocalPlayerIdentity;
import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.KeyBindings;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.body.BodyCarriers;
import com.rheinmetal.tianshu.mc.body.WalkerBody;
import com.rheinmetal.tianshu.neoforge.body.BodyWalkerRenderer;
import com.rheinmetal.tianshu.neoforge.bootstrap.NeoForgeClientBootstrap;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeBossEventWatch;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientFactWatch;
import com.rheinmetal.tianshu.mc.host.ClientPlayerIdentity;

import com.rheinmetal.tianshu.neoforge.network.TianshuNetwork;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.client.tool.ToolCallClient;
import com.rheinmetal.tianshu.api.tool.ToolTransport;
import com.rheinmetal.tianshu.neoforge.server.GameFactCollector;
import com.rheinmetal.tianshu.mc.ui.VanillaKeyBindings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

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
    public static final String MOD_ID = "tianshu_ferry";

    /** The walking carrier; everything else about a body is not an entity type. */
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<WalkerBody>> WALKER = ENTITY_TYPES.register(
            "walker",
            () -> EntityType.Builder.of(WalkerBody::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build("tianshu_walker"));

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

    /**
     * Reads every installed mod's {@code META-INF/tianshu/body_types.json} into the platform's declaration list.
     *
     * <p>This is the registration window's only chance: entity types cannot be added afterwards, and a module is not
     * required to write loader code — a file in its jar is enough. A missing file is normal (the module declares no
     * types); a malformed one is reported rather than dropped, because a silently ignored declaration means the type
     * never exists and the symptom appears much later as "the body did not spawn".</p>
     */
    private static void collectDeclaredBodyTypes() {
        System.Logger logger = System.getLogger("tianshu.adapter");
        for (net.neoforged.neoforgespi.language.IModInfo info : net.neoforged.fml.ModList.get().getMods()) {
            try {
                java.nio.file.Path path = info.getOwningFile().getFile()
                        .findResource(com.rheinmetal.tianshu.api.entity.EntityTypeDeclarations.RESOURCE_PATH);
                if (path == null || !java.nio.file.Files.isRegularFile(path)) {
                    continue;
                }
                String json = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                for (com.rheinmetal.tianshu.api.entity.EntityTypeSpec spec
                        : com.rheinmetal.tianshu.api.entity.EntityTypeDeclarations.parse(info.getModId(), json)) {
                    com.rheinmetal.tianshu.api.entity.EntityTypes.declare(spec);
                }
            } catch (RuntimeException | java.io.IOException failure) {
                logger.log(System.Logger.Level.WARNING,
                        "body_types.json of " + info.getModId() + " was not usable: " + failure);
            }
        }
    }

    public TianshuNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        collectDeclaredBodyTypes();
        // Declared entity types must exist before the loader's registration pass; draining here is the last moment at
        // which the adapter still owns that window.
        for (com.rheinmetal.tianshu.api.entity.EntityTypeSpec spec
                : com.rheinmetal.tianshu.api.entity.EntityTypes.drainForRegistration()) {
            if ("walker".equals(spec.name())) {
                continue;
            }
            ENTITY_TYPES.register(spec.name(), () -> {
                var builder = EntityType.Builder.of(WalkerBody::new, MobCategory.MISC)
                        .sized(spec.width(), spec.height())
                        .clientTrackingRange(10);
                if (spec.fireImmune()) {
                    builder.fireImmune();
                }
                return builder.build(spec.name());
            });
        }
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener((EntityAttributeCreationEvent event) -> {
            event.put(WALKER.get(), WalkerBody.createAttributes().build());
            BodyCarriers.installWalker(WALKER.get());
        });
        // Modules are platform-free: when they integrate with an optional partner, the only sane answer to
        // "is it there?" has to come from here. Registered before any module assembles, on both sides.
        TianshuRuntimePorts.register(InstalledMods.class,
                modId -> modId != null && !modId.isBlank() && ModList.get().isLoaded(modId));
        // The one network channel is registered on both sides: whoever hosts the server component still has to know
        // how a fact is shaped, and a client has to know how to receive it.
        // The tool channel's client half; the action channel is gone - actions are server tools now.



        // The tool channel's client half. It is registered once; "available" turns on only after the server's
        // catalog arrives, so a server without the component never advertises server tools.
        ToolCallClient toolCalls = new ToolCallClient((requestId, toolId, argsJson) ->
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new ToolCallRequestPacket(requestId, toolId, argsJson)));
        TianshuRuntimePorts.register(ToolTransport.class, toolCalls);
        modEventBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent registerPayloads) ->
                TianshuNetwork.register(registerPayloads, toolCalls));
        // Server-side action switches: independent of the client config, off by default, reloaded with the server.
        // A reloaded switch must change what the catalogue advertises, or the client keeps offering a dead tool.
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) ->
                com.rheinmetal.tianshu.client.tool.ServerActionTools.applySwitches(action -> {
                    var value = com.rheinmetal.tianshu.neoforge.config.ServerActionConfig.switchFor(action);
                    return value != null && value.get();
                }));
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                com.rheinmetal.tianshu.neoforge.config.ServerActionConfig.SPEC);
        // The collector is registered on both distributions: in single player the integrated server lives in this
        // same process, and "single player takes the same packet path" is a stated requirement. Its listeners only
        // act on {@code ServerPlayer}, so on a client connected to a remote server they simply never fire.
        serverCollector = new GameFactCollector();
        serverCollector.register(NeoForge.EVENT_BUS);
        com.rheinmetal.tianshu.neoforge.server.ServerToolHandler.register(NeoForge.EVENT_BUS);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) ->
                            toolCalls.onDisconnect());
            modEventBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) ->
                    event.registerEntityRenderer(WALKER.get(), BodyWalkerRenderer::new));
            clientBootstrap = new NeoForgeClientBootstrap();
            // A module's settings row may want to send the player to the controls screen; the binding is a
            // Minecraft object, so opening that screen is a platform job offered through a port.
            TianshuRuntimePorts.register(ClientKeyBindings.class, new VanillaKeyBindings());
            TianshuRuntimePorts.register(LocalPlayerIdentity.class, new ClientPlayerIdentity());
            // A module that wants a key declares it here while its jar loads; the platform registers it in the
            // key-mapping event later, so the module never touches a KeyMapping.
            TianshuRuntimePorts.register(KeyBindings.class, clientBootstrap.keyBindings());
            modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
            modEventBus.addListener(clientBootstrap::registerKeyMappings);
            modEventBus.addListener(clientBootstrap::registerReloadListeners);
            modEventBus.addListener(clientBootstrap::registerShaders);
            modEventBus.addListener(this::clientSetup);
            // A boss bar is the player's own signal that a fight started; the client is where it is visible.
            NeoForgeBossEventWatch.register(NeoForge.EVENT_BUS);
            // Facts the client observes exactly on its own: level rebuilds and opening a container.
            new NeoForgeClientFactWatch().register(NeoForge.EVENT_BUS);
        } else {
            clientBootstrap = null;
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
