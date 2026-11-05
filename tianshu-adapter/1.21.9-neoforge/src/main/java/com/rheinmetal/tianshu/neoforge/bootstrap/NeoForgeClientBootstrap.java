package com.rheinmetal.tianshu.neoforge.bootstrap;


import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
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
import com.rheinmetal.tianshu.mc.host.ClientLanguagePolicy;
import com.rheinmetal.tianshu.mc.host.ClientLanguageSnapshot;
import com.rheinmetal.tianshu.mc.host.SwingFilePicker;
import com.rheinmetal.tianshu.mc.host.LocalPresentation;
import com.rheinmetal.tianshu.mc.host.MainThreadScheduler;
import com.rheinmetal.tianshu.mc.host.ServerActionClient;
import com.rheinmetal.tianshu.mc.host.GameTextProvider;
import com.rheinmetal.tianshu.mc.host.UiHost;
import com.rheinmetal.tianshu.mc.host.GameEnvironment;
import com.rheinmetal.tianshu.api.game.NamedObjectDictionary;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.WorldFactsSource;
import com.rheinmetal.tianshu.api.game.WorldEventPublisher;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.mc.host.RegistryDictionaryProvider;
import com.rheinmetal.tianshu.mc.host.LiveGameContext;
import com.rheinmetal.tianshu.mc.host.WorldIdentityCapture;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.mc.event.NamedObjectReloadListener;
import com.rheinmetal.tianshu.mc.event.ClientEvents;
import com.rheinmetal.tianshu.mc.event.DeclaredKeyBindings;
import com.rheinmetal.tianshu.mc.event.HostKeys;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientEvents;
import com.rheinmetal.tianshu.neoforge.network.TianshuNetwork;
import com.rheinmetal.tianshu.mc.event.ClientLifecycleAdapter;
import com.rheinmetal.tianshu.mc.event.EventRegistration;
import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.neoforge.integration.TianshuIntegrationRegisterEvent;
import com.rheinmetal.tianshu.mc.ui.hud.HudAnchorEditorScreen;
import com.rheinmetal.tianshu.mc.ui.hud.HudShaderPrograms;
import com.rheinmetal.tianshu.mc.ui.hud.HudLayerHost;
import com.rheinmetal.tianshu.mc.ui.settings.SettingsLayerScreen;
import com.rheinmetal.tianshu.mc.ui.settings.TianshuSettingsModule;
import com.rheinmetal.tianshu.mc.ui.settings.TianshuSettingsScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.loading.FMLPaths;

import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.rheinmetal.tianshu.mc.bootstrap.ClientSession;
import com.rheinmetal.tianshu.mc.event.ClientLifecycleAdapter;
import com.rheinmetal.tianshu.mc.event.EventRegistration;
import com.rheinmetal.tianshu.mc.event.NamedObjectReloadListener;
import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.mc.host.ClientLanguagePolicy;
import com.rheinmetal.tianshu.mc.host.ClientLanguageSnapshot;
import com.rheinmetal.tianshu.mc.host.GameEnvironment;
import com.rheinmetal.tianshu.mc.host.GameTextProvider;
import com.rheinmetal.tianshu.mc.host.LiveGameContext;
import com.rheinmetal.tianshu.mc.host.LocalPresentation;
import com.rheinmetal.tianshu.mc.host.MainThreadScheduler;
import com.rheinmetal.tianshu.mc.host.RegistryDictionaryProvider;
import com.rheinmetal.tianshu.mc.host.SwingFilePicker;
import com.rheinmetal.tianshu.mc.host.UiHost;
import com.rheinmetal.tianshu.mc.host.WorldIdentityCapture;
import com.rheinmetal.tianshu.mc.ui.hud.HudAnchorEditorScreen;
import com.rheinmetal.tianshu.mc.ui.hud.HudLayerHost;
import com.rheinmetal.tianshu.mc.ui.hud.HudShaderPrograms;
import com.rheinmetal.tianshu.mc.ui.settings.SettingsLayerScreen;
import com.rheinmetal.tianshu.mc.ui.settings.TianshuSettingsModule;
import com.rheinmetal.tianshu.mc.ui.settings.TianshuSettingsScreen;

public final class NeoForgeClientBootstrap {
    private final ClientLanguageSnapshot languageSnapshot = new ClientLanguageSnapshot();
    private final Path gameDirectory;
    private final ClientConfig config;
    private final ServerActionClient serverActions;
    private KeyMapping voiceKey;
    private KeyMapping textInputKey;
    private final DeclaredKeyBindings keyBindings = new DeclaredKeyBindings();
    private AudioManager audioManager;
    private TianshuCoreManager coreManager;

    private ModuleSettingsSourceRegistry moduleSettingsSources;
    private TianshuSettingsModule settingsModule;
    private TianshuSettingsContributorRegistry externalSettingsContributors;
    private CoreBackedTianshuIntegrationApi integrationApi;
    private ClientDiagnosticRouter diagnosticRouter;
    private RegistryDictionaryProvider namedObjectDictionaryProvider;
    private ClientLifecycleAdapter lifecycleAdapter;
    private HudLayerHost hudHost;
    private NeoForgeClientEvents events;
    private ClientSession clientSession;
    private boolean shutdownHookRegistered;

    public NeoForgeClientBootstrap(ServerActionClient serverActions) {
        this(FMLPaths.GAMEDIR.get(), serverActions);
    }

    NeoForgeClientBootstrap(Path gameDirectory, ServerActionClient serverActions) {
        this.serverActions = Objects.requireNonNull(serverActions, "serverActions");
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
        this.config = new ClientConfig(gameDirectory);
    }

    public synchronized void start() {
        if (clientSession != null) {
            return;
        }
        ClientSession startingSession = new ClientSession();
        try {
            GameEnvironment environment = new GameEnvironment(gameDirectory);
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
            ClientSession.Ownership diagnosticsOwnership = startingSession.own(diagnosticRouter::close);
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
            GameTextProvider clientTexts = new GameTextProvider();
            // The HUD host is the platform half: it renders whatever layers are registered and knows none of them.
            HudLayerHost hudHost = new HudLayerHost(diagnosticRouter, view -> {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.setScreen(new HudAnchorEditorScreen(minecraft.screen, view));
            });
            this.hudHost = hudHost;
            // The game context port. A feature reads it; the platform only captures.
            var gameContextPort = new LiveGameContext(languageSnapshot, diagnosticRouter);
            WorldIdentityCapture worldIdentityCapture = new WorldIdentityCapture(gameDirectory);
            TianshuRuntimePorts.register(WorldFactsSource.class, worldIdentityCapture::current);

            audioManager = new AudioManager(diagnosticRouter, diagnosticRouter);
            ClientSession.Ownership audioOwnership = startingSession.own(audioManager::shutdown);

            MainThreadScheduler clientScheduler = new MainThreadScheduler();
            SwingFilePicker clientFilePicker = new SwingFilePicker(clientTexts);
            startingSession.own(clientFilePicker::close);
            UiHost clientUiHost = new UiHost(() -> settingsModule);
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
                    new LocalPresentation(),
                    serverActions
            );
            coreManager = new TianshuCoreManager(environment, audioManager, clientPorts,
                    context -> new ClientTianshuModuleAssembler(
                            createModuleProviders(context, loadQueue),
                            context,
                            moduleSettingsSources));
            TianshuCoreManager assembledCoreManager = coreManager;
            ClientSession.Ownership coreOwnership = startingSession.own(
                    () -> assembledCoreManager.destroy().join()
            );

            TianshuClientRuntime clientRuntime = new TianshuClientRuntime(
                    new ClientRuntimeServices(coreManager, audioManager, diagnosticRouter),
                    () -> diagnosticRouter.info("neoforge.world.session_started"),
                    failure -> diagnosticRouter.error("neoforge.world.session_lifecycle_failed", failure)
            );
            lifecycleAdapter = new ClientLifecycleAdapter(clientRuntime);
            ClientLifecycleAdapter currentLifecycleAdapter = lifecycleAdapter;
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
            WorldEventHooks.bind(worldEventPublisher, diagnosticRouter);
            startingSession.own(() -> WorldEventHooks.clear(worldEventPublisher));

            externalSettingsContributors = new TianshuSettingsContributorRegistry();
            integrationApi = new CoreBackedTianshuIntegrationApi(coreManager);
            CoreBackedTianshuIntegrationApi currentIntegrationApi = integrationApi;
            TianshuIntegrationAccess.publish(currentIntegrationApi);
            startingSession.own(() -> TianshuIntegrationAccess.clear(currentIntegrationApi));
            settingsModule = new TianshuSettingsModule(createSettingsRegistrySource(clientPorts), config, diagnosticRouter);
            NeoForge.EVENT_BUS.post(new TianshuIntegrationRegisterEvent(currentIntegrationApi, externalSettingsContributors));

            events = new NeoForgeClientEvents(new ClientEvents(
                    coreManager,
                    settingsModule,
                    lifecycleAdapter,
                    hudHost,
                    worldIdentityCapture,
                    () -> voiceKey,
                    () -> textInputKey,
                    keyBindings,
                    clientUiHost,
                    serverActions,
                    TianshuNetwork::protocolVersion,
                    diagnosticRouter
            ));
            NeoForgeClientEvents currentEvents = events;
            EventRegistration eventRegistration = EventRegistration.register(
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
    public DeclaredKeyBindings keyBindings() {
        return keyBindings;
    }

    public synchronized void registerKeyMappings(RegisterKeyMappingsEvent event) {
        // HostKeys owns the category: 1.21.9 takes a registered KeyMapping.Category, not a translation key, and
        // both host keys have to share one instance or the controls screen lists the category twice.
        voiceKey = HostKeys.voice();
        event.register(voiceKey);
        // Always registered, so the player can rebind it from the controls screen; whether pressing it does
        // anything is decided by whether a module is subscribed to TEXT_SUBMITTED, not by this registration.
        textInputKey = HostKeys.textInput();
        event.register(textInputKey);
        keyBindings.registerAll(event::register);
    }

    public synchronized void registerReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(ResourceLocation.parse("tianshu:named_objects"), new NamedObjectReloadListener(
                this::onResourcesReloaded,
                diagnosticRouter == null ? com.rheinmetal.tianshu.api.LogSink.NOOP : diagnosticRouter
        ));
    }

    /** A reload changes translations and resources, so everything measured or resolved from them must be dropped. */
    private void onResourcesReloaded() {
        refreshNamedObjectSnapshot();
        HudLayerHost host = hudHost;
        if (host != null) {
            host.onResourcesReloaded();
        }
    }

    /**
     * Registers one descriptor per declared HUD program.
     *
     * <p>The renderer applies a program itself through {@code RenderSystem.setShader}, so the platform only states
     * which asset stands for each id: the shader manager compiles it from the pack that just loaded, and a
     * declaration that cannot be parsed is cleared so the layer simply draws nothing.</p>
     */
    private void registerHudShaders() {
        for (java.util.Map.Entry<HudProgramId, String> declaration : HudShaderCatalog.declarations().entrySet()) {
            ResourceLocation location = ResourceLocation.tryParse(declaration.getValue());
            if (location == null) {
                HudShaderPrograms.register(declaration.getKey(), null);
                if (diagnosticRouter != null) {
                    diagnosticRouter.warn("neoforge.hud.shader_declaration_invalid id=" + declaration.getValue());
                }
                continue;
            }
            HudShaderPrograms.register(declaration.getKey(),
                    new com.rheinmetal.tianshu.mc.compat.RenderPipelineHudShader(location, diagnosticRouter));
        }
    }

    public synchronized void shutdown() {
        ClientSession currentSession = clientSession;
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
    private synchronized RegistryDictionaryProvider createNamedObjectDictionary() {
        if (namedObjectDictionaryProvider == null) {
            namedObjectDictionaryProvider = new RegistryDictionaryProvider(languageSnapshot);
            TianshuRuntimePorts.register(NamedObjectDictionary.class, namedObjectDictionaryProvider);
        }
        return namedObjectDictionaryProvider;
    }

    private void refreshNamedObjectSnapshot() {
        if (namedObjectDictionaryProvider == null) {
            namedObjectDictionaryProvider = new RegistryDictionaryProvider(languageSnapshot);
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
