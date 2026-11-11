package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.assembly.ClientKeyBindings;
import com.rheinmetal.tianshu.api.assembly.KeyBindings;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.event.NamedObjectReloadListener;
import com.rheinmetal.tianshu.mc.ui.VanillaKeyBindings;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The Fabric client entry point.
 *
 * <p>Everything registered here is a loader hook whose timing matters: keys must exist during client init, the
 * reload listener must be in place before the first resource load, and the session itself is built once the client
 * is ready. The assembly lives in {@link FabricClientBootstrap}.</p>
 */
public final class TianshuFabricClient implements ClientModInitializer {

    private static FabricClientBootstrap bootstrap;

    @Override
    public void onInitializeClient() {
        bootstrap = new FabricClientBootstrap();
        // A module's settings row may want to send the player to the controls screen; the binding is a Minecraft
        // object, so opening that screen is a platform job offered through a port.
        TianshuRuntimePorts.register(ClientKeyBindings.class, new VanillaKeyBindings());
        // A module that wants a key declares it here while its jar loads; the platform registers it below, so the
        // module never touches a KeyMapping.
        TianshuRuntimePorts.register(KeyBindings.class, bootstrap.keyBindings());
        bootstrap.registerKeyMappings();
        // Client-observed world facts: only what Fabric notifies natively (a container screen opening).
        FabricClientFactWatch.register();
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(identifiable("named_objects", bootstrap.reloadListener()));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> bootstrap.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> bootstrap.shutdown());
    }

    /** Names a shared reload listener for Fabric, which requires an id to order and deduplicate listeners. */
    private static IdentifiableResourceReloadListener identifiable(String path, PreparableReloadListener delegate) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(com.rheinmetal.tianshu.api.ModIdentity.MOD_ID, path);
        return new IdentifiableResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return id;
            }

            @Override
            public CompletableFuture<Void> reload(
                    PreparationBarrier barrier,
                    ResourceManager resourceManager,
                    ProfilerFiller prepareProfiler,
                    ProfilerFiller applyProfiler,
                    Executor backgroundExecutor,
                    Executor gameExecutor) {
                return delegate.reload(barrier, resourceManager, prepareProfiler, applyProfiler,
                        backgroundExecutor, gameExecutor);
            }
        };
    }

}
