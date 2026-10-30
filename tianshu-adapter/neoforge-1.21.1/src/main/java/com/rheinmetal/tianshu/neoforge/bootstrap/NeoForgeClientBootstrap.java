package com.rheinmetal.tianshu.neoforge.bootstrap;


import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.mojang.blaze3d.platform.InputConstants;
import com.rheinmetal.tianshu.client.audio.AudioManager;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticChatCatalogue;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticPolicy;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticRouter;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticMessageSink;
import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.runtime.ClientRuntimeServices;
import com.rheinmetal.tianshu.client.runtime.TianshuClientRuntime;
import com.rheinmetal.tianshu.client.runtime.module.ClientOnnxRuntimeModuleInstaller;
import com.rheinmetal.tianshu.client.runtime.module.ClientTianshuModuleAssembler;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerHost;
import com.rheinmetal.tianshu.client.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ExternalSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsContributorRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.config.PropertyModuleConfigStores;
import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsSourceRegistry;
import com.rheinmetal.tianshu.integration.CoreBackedTianshuIntegrationApi;
import com.rheinmetal.tianshu.integration.TianshuIntegrationAccess;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguageSnapshot;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientFilePicker;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientPresentation;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientScheduler;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeServerActionClient;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientTextProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientUiHost;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeEnvironment;
import com.rheinmetal.tianshu.api.game.NamedObjectDictionary;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.WorldFactsSource;
import com.rheinmetal.tianshu.api.game.WorldEventPublisher;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeNamedObjectDictionaryProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeGameContextPort;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeWorldIdentityCapture;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.event.NamedObjectReloadListener;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientEvents;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientLifecycleAdapter;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeEventRegistration;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeWorldEventHooks;
import com.rheinmetal.tianshu.neoforge.integration.TianshuIntegrationRegisterEvent;
import com.rheinmetal.tianshu.neoforge.ui.hud.HudAnchorEditorScreen;
import com.rheinmetal.tianshu.neoforge.ui.hud.HudShaderPrograms;
import com.rheinmetal.tianshu.neoforge.ui.hud.NeoForgeHudHost;
import com.rheinmetal.tianshu.neoforge.ui.settings.SettingsLayerScreen;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class NeoForgeClientBootstrap {
    private final ClientLanguageSnapshot languageSnapshot = new ClientLanguageSnapshot();
    private final Path gameDirectory;
    private final ClientConfig config;
    private KeyMapping voiceKey;
    private KeyMapping textInputKey;
    private final com.rheinmetal.tianshu.neoforge.event.NeoForgeKeyBindings keyBindings = new com.rheinmetal.tianshu.neoforge.event.NeoForgeKeyBindings();
    private AudioManager audioManager;
    private TianshuCoreManager coreManager;

    private ModuleSettingsSourceRegistry moduleSettingsSources;
    private TianshuSettingsModule settingsModule;
    private TianshuSettingsContributorRegistry externalSettingsContributors;
    private CoreBackedTianshuIntegrationApi integrationApi;
    private ClientDiagnosticRouter diagnosticRouter;
    private NeoForgeNamedObjectDictionaryProvider namedObjectDictionaryProvider;
    private NeoForgeClientLifecycleAdapter lifecycleAdapter;
    private NeoForgeHudHost hudHost;
    private NeoForgeClientEvents events;
    private NeoForgeClientSession clientSession;
    private boolean shutdownHookRegistered;

    public NeoForgeClientBootstrap() {
        this(FMLPaths.GAMEDIR.get());
    }

    NeoForgeClientBootstrap(Path gameDirectory) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
        this.config = new ClientConfig(gameDirectory);
    }

    public synchronized void start() {
        if (clientSession != null) {
            return;
        }
        NeoForgeClientSession startingSession = new NeoForgeClientSession();
        try {
            NeoForgeEnvironment environment = new NeoForgeEnvironment(gameDirectory);
            ClientDiagnosticMessageSink chatSink = message -> Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null && message != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    message.translationKey(), message.arguments().toArray()), false
                    );
                }
            });
            ClientDiagnosticChatCatalogue chatCatalogue = new ClientDiagnosticChatCatalogue();
            diagnosticRouter = new ClientDiagnosticRouter(
                    gameDirectory,
                    new ClientDiagnosticPolicy(this::diagnosticModuleIds),
                    config::isDebugEnabled,
                    chatSink,
                    chatCatalogue
            );
            NeoForgeClientSession.Ownership diagnosticsOwnership = startingSession.own(diagnosticRouter::close);
            environment.bindDiagnostics(diagnosticRouter);
            environment.bindLogs(diagnosticRouter);
            // A subscriber that throws is contained by the bus; report it where every other failure goes,
            // so "a key stopped working" has a line in the diagnostics log instead of a silent disappearance.
            ClientDiagnosticRouter failureReporter = diagnosticRouter;
            com.rheinmetal.tianshu.api.assembly.SignalFailures.setHandler((bus, channel, failure) ->
                    failureReporter.error("signal." + bus + ".subscriber_failed channel=" + channel, failure));
            diagnosticRouter.info("neoforge.client.bootstrap_starting");
            Path moduleConfigDirectory = gameDirectory.resolve("config").resolve("Tianshu").resolve("module");
            PropertyModuleConfigStores moduleConfigs = new PropertyModuleConfigStores(moduleConfigDirectory, diagnosticRouter);
            // The platform supplies the dictionary snapshot and nothing else: the module that indexes it
            // owns the index, its cache and its lifetime.
            createNamedObjectDictionary();
            refreshNamedObjectSnapshot();
            NeoForgeClientTextProvider clientTexts = new NeoForgeClientTextProvider();
            // The HUD host is the platform half: it renders whatever layers are registered and knows none of them.
            NeoForgeHudHost hudHost = new NeoForgeHudHost(diagnosticRouter, view -> {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.setScreen(new HudAnchorEditorScreen(minecraft.screen, view));
            });
            this.hudHost = hudHost;
            // The game context port. A feature reads it; the platform only captures.
            var gameContextPort = new NeoForgeGameContextPort(languageSnapshot, diagnosticRouter);
            NeoForgeWorldIdentityCapture worldIdentityCapture = new NeoForgeWorldIdentityCapture(gameDirectory);
            TianshuRuntimePorts.register(WorldFactsSource.class, worldIdentityCapture::current);

            audioManager = new AudioManager(diagnosticRouter, diagnosticRouter);
            NeoForgeClientSession.Ownership audioOwnership = startingSession.own(audioManager::shutdown);

            NeoForgeClientScheduler clientScheduler = new NeoForgeClientScheduler();
            NeoForgeClientFilePicker clientFilePicker = new NeoForgeClientFilePicker(clientTexts);
            startingSession.own(clientFilePicker::close);
            NeoForgeClientUiHost clientUiHost = new NeoForgeClientUiHost(() -> settingsModule);
            // One gate for every native model load in the process: retrieval claims it as the primary
            // lane and the speech modules queue behind it, so their native peaks never add up.
            NativeModelLoadQueue loadQueue = new NativeModelLoadQueue();
            moduleSettingsSources = new ModuleSettingsSourceRegistry();
            ClientPorts clientPorts = new ClientPorts(
                    clientScheduler,
                    clientUiHost,
                    clientTexts,
                    clientFilePicker,
                    createSettingsLayerHost(),
                    audioManager instanceof com.rheinmetal.tianshu.client.host.ClientAudioDeviceCatalog catalog ? catalog : null,
                    moduleConfigs,
                    loadQueue,
                    hudHost,
                    gameContextPort,
                    config::isDebugEnabled,
                    chatCatalogue,
                    new NeoForgeClientPresentation(),
                    new NeoForgeServerActionClient()
            );
            coreManager = new TianshuCoreManager(environment, audioManager, clientPorts,
                    context -> new ClientTianshuModuleAssembler(
                            createModuleProviders(context, loadQueue),
                            context,
                            moduleSettingsSources));
            TianshuCoreManager assembledCoreManager = coreManager;
            NeoForgeClientSession.Ownership coreOwnership = startingSession.own(
                    () -> assembledCoreManager.destroy().join()
            );

            TianshuClientRuntime clientRuntime = new TianshuClientRuntime(
                    new ClientRuntimeServices(coreManager, audioManager, diagnosticRouter),
                    () -> diagnosticRouter.info("neoforge.world.session_started"),
                    failure -> diagnosticRouter.error("neoforge.world.session_lifecycle_failed", failure)
            );
            lifecycleAdapter = new NeoForgeClientLifecycleAdapter(clientRuntime);
            NeoForgeClientLifecycleAdapter currentLifecycleAdapter = lifecycleAdapter;
            startingSession.own(currentLifecycleAdapter::onClientShutdown);
            coreOwnership.release();
            audioOwnership.release();
            diagnosticsOwnership.release();
            currentLifecycleAdapter.onClientReady();
            startingSession.own(worldIdentityCapture::clear);

            // The game's own event stream: the adapter observes, the runtime publishes. Binding the publisher —
            // rather than a feature runtime — is what lets the host report events whether or not a feature is installed.
            // The anonymous implementation exists for `hasSubscribers`: with no consumer the adapter can skip the
            // payload/envelope entirely instead of routing an event nobody reads.
            WorldEventPublisher worldEventPublisher = new WorldEventPublisher() {
                @Override
                public void publish(WorldEventPayload event) {
                    coreManager.publishWorldEvent(event);
                }

                @Override
                public boolean hasSubscribers() {
                    return coreManager.hasWorldEventSubscribers();
                }
            };
            NeoForgeWorldEventHooks.bind(worldEventPublisher, diagnosticRouter);
            startingSession.own(() -> NeoForgeWorldEventHooks.clear(worldEventPublisher));

            externalSettingsContributors = new TianshuSettingsContributorRegistry();
            integrationApi = new CoreBackedTianshuIntegrationApi(coreManager);
            CoreBackedTianshuIntegrationApi currentIntegrationApi = integrationApi;
            TianshuIntegrationAccess.publish(currentIntegrationApi);
            startingSession.own(() -> TianshuIntegrationAccess.clear(currentIntegrationApi));
            settingsModule = new TianshuSettingsModule(createSettingsRegistrySource(clientPorts), config, diagnosticRouter);
            NeoForge.EVENT_BUS.post(new TianshuIntegrationRegisterEvent(currentIntegrationApi, externalSettingsContributors));

            events = new NeoForgeClientEvents(
                    coreManager,
                    settingsModule,
                    lifecycleAdapter,
                    hudHost,
                    worldIdentityCapture,
                    () -> voiceKey,
                () -> textInputKey,
                    keyBindings,
                    clientUiHost,
                    diagnosticRouter
            );
            NeoForgeClientEvents currentEvents = events;
            NeoForgeEventRegistration eventRegistration = NeoForgeEventRegistration.register(
                    () -> NeoForge.EVENT_BUS.register(currentEvents),
                    () -> NeoForge.EVENT_BUS.unregister(currentEvents)
            );
            startingSession.own(() -> {
                currentEvents.resetVoiceInputState();
                eventRegistration.close();
            });

            registerShutdownHook();
            clientSession = startingSession;
        } catch (RuntimeException | Error failure) {
            try {
                startingSession.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            clearSessionReferences();
            throw failure;
        }
    }

    /** The registry a module declares its own key into. */
    public com.rheinmetal.tianshu.neoforge.event.NeoForgeKeyBindings keyBindings() {
        return keyBindings;
    }

    public synchronized void registerKeyMappings(RegisterKeyMappingsEvent event) {
        voiceKey = new KeyMapping(
                "key.tianshu.activate",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.tianshu"
        );
        event.register(voiceKey);
        // Always registered, so the player can rebind it from the controls screen; whether pressing it does
        // anything is decided by whether a module is subscribed to TEXT_SUBMITTED, not by this registration.
        textInputKey = new KeyMapping(
                "key.tianshu.text_input",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "key.categories.tianshu"
        );
        event.register(textInputKey);
        keyBindings.registerAll(event);
    }

    public synchronized void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new NamedObjectReloadListener(
                this::onResourcesReloaded,
                diagnosticRouter == null ? com.rheinmetal.tianshu.api.LogSink.NOOP : diagnosticRouter
        ));
    }

    /** A reload changes translations and resources, so everything measured or resolved from them must be dropped. */
    private void onResourcesReloaded() {
        refreshNamedObjectSnapshot();
        NeoForgeHudHost host = hudHost;
        if (host != null) {
            host.onResourcesReloaded();
        }
    }

    /**
     * Creates the HUD programs features declared.
     *
     * <p>The platform owns the {@code ShaderInstance} lifecycle and the failure fallback; the features own the files,
     * their uniforms, their binding and their look. Nothing here names a feature: an absent one declares nothing, its
     * program simply has no shader, and that layer draws nothing.</p>
     */
    public void registerShaders(RegisterShadersEvent event) {
        for (java.util.Map.Entry<HudProgramId, String> declaration : HudShaderCatalog.declarations().entrySet()) {
            registerShader(event, declaration.getKey(), declaration.getValue());
        }
    }

    private void registerShader(RegisterShadersEvent event, HudProgramId program, String shaderId) {
        ResourceLocation shaderLocation = ResourceLocation.tryParse(shaderId);
        if (shaderLocation == null) {
            HudShaderPrograms.register(program, null);
            if (diagnosticRouter != null) {
                diagnosticRouter.warn("neoforge.hud.shader_declaration_invalid id=" + shaderId);
            }
            return;
        }
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), shaderLocation, DefaultVertexFormat.POSITION_TEX),
                    shader -> HudShaderPrograms.register(program, shader)
            );
        } catch (IOException | RuntimeException failure) {
            // A visual shader is optional; clear this handle so the layer simply draws nothing for the program.
            HudShaderPrograms.register(program, null);
            if (diagnosticRouter != null) {
                diagnosticRouter.error("neoforge.hud.shader_registration_failed name=" + shaderLocation, failure);
            }
        }
    }

    public synchronized void shutdown() {
        NeoForgeClientSession currentSession = clientSession;
        if (currentSession == null) {
            return;
        }
        clientSession = null;
        com.rheinmetal.tianshu.api.LogSink currentLogSink = diagnosticRouter == null
                ? com.rheinmetal.tianshu.api.LogSink.NOOP : diagnosticRouter;
        currentLogSink.info("neoforge.client.shutdown_starting");
        try {
            currentSession.close();
        } catch (RuntimeException failure) {
            currentLogSink.error("neoforge.client.shutdown_failed", failure);
        } finally {
            clearSessionReferences();
        }
        // No "shutdown_completed" line here on purpose: the router writes diagnostic.log_closed as its last
        // record, and anything logged after currentSession.close() lands in a closed writer.
    }

    /**
     * The modules that are part of this session, read at call time.
     *
     * <p>Diagnostics used to be gated by a hardcoded list of five ids, which silently dropped every other
     * module's events (modules outside the old list). The gate now asks what the session actually assembled, so a new
     * module's diagnostics appear by being loaded instead of by editing the host.</p>
     */
    private Set<String> diagnosticModuleIds() {
        TianshuCoreManager core = coreManager;
        if (core == null) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (TianshuManagedModule module : core.managedModules()) {
            ids.add(module.moduleId());
        }
        ids.addAll(core.integrationModuleIds());
        // The platform's own components are not feature modules, so the session's module list cannot name them.
        ids.add(com.rheinmetal.tianshu.protocol.ProtocolModuleIds.PLATFORM_AUDIO);
        ids.add(com.rheinmetal.tianshu.protocol.ProtocolModuleIds.PLATFORM_ONNX_RUNTIME);
        return ids;
    }

    private void clearSessionReferences() {
        com.rheinmetal.tianshu.api.assembly.SignalFailures.setHandler(null);
        HudShaderPrograms.clear();
        events = null;
        integrationApi = null;
        lifecycleAdapter = null;
        hudHost = null;
        coreManager = null;
        audioManager = null;
        diagnosticRouter = null;
        settingsModule = null;
        externalSettingsContributors = null;
    }

    /**
     * The composition root decides which modules a session runs.
     *
     * <p>Removing a line here removes the module: the core keeps no list of its own, so an absent
     * provider means the installer is never created and nothing refers to it at runtime.</p>
     */
    private List<TianshuModuleProvider> createModuleProviders(
            TianshuModuleAssemblyContext context,
            NativeModelLoadQueue loadQueue) {
        return List.of(
                registrar -> registrar.addModule(new ClientOnnxRuntimeModuleInstaller())
        );
    }


    /**
     * A layer is shown on NeoForge's GUI layer stack, which keeps the settings page below it alive: that
     * page keeps its widgets and its unsaved state, and the stack draws it under the new layer.
     */
    private SettingsLayerHost createSettingsLayerHost() {
        return layer -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof TianshuSettingsScreen settingsScreen) {
                ClientHooks.pushGuiLayer(minecraft, new SettingsLayerScreen(settingsScreen, layer));
            } else {
                layer.onClosed().run();
            }
        };
    }

    private TianshuSettingsRegistrySource createSettingsRegistrySource(ClientPorts ports) {
        TianshuSettingsRegistrySource moduleSource = new ModuleSettingsRegistrySource(coreManager::managedModules);
        TianshuSettingsRegistrySource externalSource = new ExternalSettingsRegistrySource(externalSettingsContributors);
        // Feature pages arrive through `moduleSettingsSources`: every feature contributes its own page when it is
        // assembled, so the platform holds no list of them.
        return CompositeSettingsRegistrySource.of(
                moduleSource,
                externalSource,
                moduleSettingsSources
        );
    }

    /**
     * Publishes the game's object dictionary and nothing else.
     *
     * <p>The dictionary is a platform fact, so it is a port; the index built from it belongs to the module
     * that reads speech, and that module decides where it lives and when it is rebuilt. Registering it here
     * is the whole extent of the platform's involvement.</p>
     */
    private synchronized NeoForgeNamedObjectDictionaryProvider createNamedObjectDictionary() {
        if (namedObjectDictionaryProvider == null) {
            namedObjectDictionaryProvider = new NeoForgeNamedObjectDictionaryProvider(languageSnapshot);
            TianshuRuntimePorts.register(NamedObjectDictionary.class, namedObjectDictionaryProvider);
        }
        return namedObjectDictionaryProvider;
    }

    private void refreshNamedObjectSnapshot() {
        if (namedObjectDictionaryProvider == null) {
            namedObjectDictionaryProvider = new NeoForgeNamedObjectDictionaryProvider(languageSnapshot);
        }
        try {
            languageSnapshot.update(ClientLanguagePolicy.captureCurrentLanguageCode());
            namedObjectDictionaryProvider.refresh();
        } catch (RuntimeException failure) {
            if (diagnosticRouter != null) {
                diagnosticRouter.error("neoforge.ir.dictionary_refresh_failed", failure);
            }
        }
    }

    private void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (diagnosticRouter != null) {
                diagnosticRouter.info("neoforge.jvm.shutdown_fallback");
            }
            shutdown();
        }, "Tianshu-Shutdown-Hook"));
    }
}
