package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.entity.EntityTypeDeclarations;
import com.rheinmetal.tianshu.api.entity.EntityTypeSpec;
import com.rheinmetal.tianshu.api.entity.EntityTypes;
import com.rheinmetal.tianshu.mc.body.BodyCarriers;
import com.rheinmetal.tianshu.mc.body.WalkerBody;
import com.rheinmetal.tianshu.api.assembly.InstalledModIds;
import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.ModResources;
import com.rheinmetal.tianshu.mc.resource.ModResourceFiles;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.client.tool.ToolCallClient;
import com.rheinmetal.tianshu.mc.network.ToolCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallRequestPacket;
import com.rheinmetal.tianshu.mc.network.ToolCallResultPacket;
import com.rheinmetal.tianshu.api.tool.ToolTransport;
import com.rheinmetal.tianshu.protocol.ProtocolWireVersion;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The Fabric entry point, loaded on both sides.
 *
 * <p>Two jobs belong here rather than to the client initializer: answering "is that mod installed?" — which every
 * module asks before it touches an optional partner — and registering the one network channel, which has to be
 * known before any world connects.</p>
 *
 * <p>The optional server component is {@link FabricGameFactCollector} plus {@link FabricServerActionHandler}: it
 * collects this loader's server facts and answers action requests, off by default and operator-only. The action
 * channel is registered on the client too, so a module can request an action and receive the honest
 * {@code FAILED} answer instead of a promise the platform cannot keep.</p>
 */
public final class TianshuFabric implements ModInitializer {

    /** The wire protocol epoch, matching the NeoForge adapter's channel version. */
    public static final String PROTOCOL_VERSION = ProtocolWireVersion.VERSION;

    /** The tool channel client: only on the client distribution. */
    private static ToolCallClient toolClient;

    public static ToolCallClient toolClient() {
        return toolClient;
    }

    /**
     * Registers the body types every installed mod declares in {@code META-INF/tianshu/body_types.json}.
     *
     * <p>A module needs no loader code for this: a file in its jar is the whole contract. A missing file is normal; a
     * malformed one is reported, because a silently ignored declaration means the type never exists and the symptom
     * appears much later as "the body did not spawn".</p>
     */
    private static void registerDeclaredBodyTypes() {
        for (net.fabricmc.loader.api.ModContainer container : FabricLoader.getInstance().getAllMods()) {
            try {
                java.util.Optional<java.nio.file.Path> declared =
                        container.findPath(EntityTypeDeclarations.RESOURCE_PATH);
                if (declared.isEmpty() || !java.nio.file.Files.isRegularFile(declared.get())) {
                    continue;
                }
                String json = java.nio.file.Files.readString(declared.get(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (EntityTypeSpec spec
                        : EntityTypeDeclarations
                        .parse(container.getMetadata().getId(), json)) {
                    EntityTypes.declare(spec);
                }
            } catch (RuntimeException | java.io.IOException failure) {
                LogSink.SYSTEM.warn("body.type_declarations.invalid mod=" + container.getMetadata().getId(), failure);
            }
        }
        for (var spec : BodyCarriers.registrationTypes(EntityTypes.drainForRegistration())) {
            var type = Registry.register(BuiltInRegistries.ENTITY_TYPE, spec.id(), BodyCarriers.createType(spec));
            BodyCarriers.track(spec, type);
            FabricDefaultAttributeRegistry.register(type, WalkerBody.createAttributes());
        }
    }

    @Override
    public void onInitialize() {
        registerDeclaredBodyTypes();
        // Modules are platform-free: the only sane answer to "is it there?" has to come from here.
        TianshuRuntimePorts.register(InstalledMods.class,
                modId -> modId != null && !modId.isBlank() && FabricLoader.getInstance().isModLoaded(modId));
        // A module that reads partner content (guides, data files) cannot open a jar itself: it enumerates the
        // mods and asks for resource paths through this port. Nothing here knows what the content means.
        TianshuRuntimePorts.register(InstalledModIds.class,
                () -> FabricLoader.getInstance().getAllMods().stream()
                        .map(container -> container.getMetadata().getId())
                        .toList());
        TianshuRuntimePorts.register(ModResources.class, ModResourceFiles.over((modId, path) ->
                FabricLoader.getInstance().getModContainer(modId)
                        .flatMap(container -> container.findPath(path))
                        .orElse(null)));
        // Payload types first: a receiver may only be registered for a type the registry already knows, on both
        // sides. Getting this order wrong fails the server at startup with "no payload type has been registered".
        registerPayloads();
        // A dedicated server has no client classes to touch: everything that names one lives in the client branch.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.CLIENT) {
            toolClient = new ToolCallClient((requestId, toolId, argsJson, callerModule) -> ClientPlayNetworking.send(
                new ToolCallRequestPacket(requestId, toolId, argsJson, callerModule)));
            TianshuRuntimePorts.register(ToolTransport.class, toolClient);
            registerClientReceivers();
        }
        // The optional server component: present on every distribution, listens only to server events, and sends
        // only to a client that registered the channel.
        new FabricGameFactCollector().register();
        // The action channel's server half: off by default, one switch per action, operator-only, idempotent.
        FabricServerActionConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
        FabricServerToolHandler.register();
    }

    /**
     * The one channel Tianshu owns.
     *
     * <p>The payloads themselves are the shared records; only the registration and the thread hop are Fabric's.
     * The server component registers the same payload types and is what lets an integrated server
     * send facts on this side; the client half is registered separately so a dedicated server never touches
     * a client class. The wire shape is the shared records either way.</p>
     */
    private static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(GameFactBatchPacket.TYPE, GameFactBatchPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ToolCallResultPacket.TYPE, ToolCallResultPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ToolCatalogPacket.TYPE, ToolCatalogPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ToolCallRequestPacket.TYPE, ToolCallRequestPacket.STREAM_CODEC);
    }

    /** The client half of the one channel: receiving what the server component (or an integrated server) sends. */
    private static void registerClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(GameFactBatchPacket.TYPE, (packet, context) -> {
            var events = packet.toEvents();
            if (events.isEmpty()) {
                return;
            }
            context.client().execute(() -> events.forEach(com.rheinmetal.tianshu.mc.event.WorldEventHooks::publish));
        });
        ClientPlayNetworking.registerGlobalReceiver(ToolCallResultPacket.TYPE,
                (packet, context) -> context.client().execute(() -> toolClient.receiveResult(packet.requestId(), packet.ok(), packet.valueJson(), packet.errorCode(), packet.message())));
        ClientPlayNetworking.registerGlobalReceiver(ToolCatalogPacket.TYPE,
                (packet, context) -> context.client().execute(() -> toolClient.receiveCatalog(packet.tools())));
    }


}
