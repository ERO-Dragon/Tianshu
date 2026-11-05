package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.SignalFailures;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;
import com.rheinmetal.tianshu.api.game.NamedObjectDictionary;
import com.rheinmetal.tianshu.api.game.WorldEventPublisher;
import com.rheinmetal.tianshu.api.game.WorldFactsSource;
import com.rheinmetal.tianshu.client.audio.AudioManager;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticChatCatalogue;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticMessageSink;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticPolicy;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticRouter;
import com.rheinmetal.tianshu.client.runtime.ClientRuntimeServices;
import com.rheinmetal.tianshu.client.runtime.TianshuClientRuntime;
import com.rheinmetal.tianshu.client.runtime.module.ClientOnnxRuntimeModuleInstaller;
import com.rheinmetal.tianshu.client.runtime.module.ClientTianshuModuleAssembler;
import com.rheinmetal.tianshu.client.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ExternalSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsSourceRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsContributorRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.config.PropertyModuleConfigStores;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.integration.CoreBackedTianshuIntegrationApi;
import com.rheinmetal.tianshu.integration.TianshuIntegrationAccess;
import com.rheinmetal.tianshu.mc.bootstrap.ClientSession;
import com.rheinmetal.tianshu.mc.event.ClientEvents;
import com.rheinmetal.tianshu.mc.event.ClientLifecycleAdapter;
import com.rheinmetal.tianshu.mc.event.DeclaredKeyBindings;
import com.rheinmetal.tianshu.mc.event.NamedObjectReloadListener;
import com.rheinmetal.tianshu.mc.event.WorldEventHooks;
import com.rheinmetal.tianshu.mc.host.ClientLanguageSnapshot;
import com.rheinmetal.tianshu.mc.host.GameEnvironment;
import com.rheinmetal.tianshu.mc.host.GameTextProvider;
import com.rheinmetal.tianshu.mc.host.LiveGameContext;
import com.rheinmetal.tianshu.mc.host.LocalPresentation;
import com.rheinmetal.tianshu.mc.host.MainThreadScheduler;
import com.rheinmetal.tianshu.mc.host.RegistryDictionaryProvider;
import com.rheinmetal.tianshu.mc.host.SwingFilePicker;
import com.rheinmetal.tianshu.mc.host.UiHost;
import com.rheinmetal.tianshu.mc.event.HostKeys;
import com.rheinmetal.tianshu.mc.host.WorldIdentityCapture;
import com.rheinmetal.tianshu.mc.ui.hud.HudAnchorEditorScreen;
import com.rheinmetal.tianshu.mc.ui.hud.HudLayerHost;
import com.rheinmetal.tianshu.mc.ui.hud.HudShaderPrograms;
import com.rheinmetal.tianshu.mc.ui.settings.SettingsLayerScreen;
import com.rheinmetal.tianshu.mc.ui.settings.TianshuSettingsModule;
import com.rheinmetal.tianshu.mc.ui.settings.TianshuSettingsScreen;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the client session for Fabric.
 *
 * <p>This is the composition root: it decides which platform-neutral objects exist and in what order they are
 * created. The decisions about what happens on a tick, a frame or a session change are in
 * {@link ClientEvents} — shared with the NeoForge adapter — so the two roots assemble the same product and only
 * the registration APIs differ.</p>
 *
 * <p>Every resource that can be released is handed to the {@link ClientSession}; nothing here is torn down by
 * hand, and closing the session twice is a no-op.</p>
 */
public final class FabricClientBootstrap {
    private final ClientLanguageSnapshot languageSnapshot = new ClientLanguageSnapshot();
    private final Path gameDirectory;
    private final FabricClientConfig config;
    private final DeclaredKeyBindings keyBindings = new DeclaredKeyBindings();
    private final ClientSession session = new ClientSession();
    private KeyMapping voiceKey;
    private KeyMapping textInputKey;
    private AudioManager audioManager;
    private TianshuCoreManager coreManager;
    private ModuleSettingsSourceRegistry moduleSettingsSources;
    private TianshuSettingsModule settingsModule;
    private TianshuSettingsContributorRegistry externalSettingsContributors;
    private ClientDiagnosticRouter diagnosticRouter;
    private RegistryDictionaryProvider namedObjectDictionaryProvider;
    private ClientLifecycleAdapter lifecycleAdapter;
    private HudLayerHost hudHost;
    private ClientEvents events;
    private boolean started;
    private boolean shutdownHookRegistered;

    public FabricClientBootstrap() {
        this(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
    }

    FabricClientBootstrap(Path gameDirectory) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
        this.config = new FabricClientConfig(gameDirectory);
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        try {
            GameEnvironment environment = new GameEnvironment(gameDirectory);
            ClientDiagnosticMessageSink chatSink = message -> Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null && message != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.translatable(message.translationKey(), message.arguments().toArray()), false);
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
            session.own(diagnosticRouter::close);
            environment.bindDiagnostics(diagnosticRouter);
            environment.bindLogs(diagnosticRouter);
            ClientDiagnosticRouter failureReporter = diagnosticRouter;
            SignalFailures.setHandler((bus, channel, failure) ->
                    failureReporter.error("signal." + bus + ".subscriber_failed channel=" + channel, failure));
            diagnosticRouter.info("fabric.client.bootstrap_starting");

            Path moduleConfigDirectory = gameDirectory.resolve("config").resolve("Tianshu").resolve("module");
            PropertyModuleConfigStores moduleConfigs = new PropertyModuleConfigStores(moduleConfigDirectory, diagnosticRouter);
            createNamedObjectDictionary();
            refreshNamedObjectSnapshot();
            GameTextProvider clientTexts = new GameTextProvider();
            HudLayerHost hudHost = new HudLayerHost(diagnosticRouter, view -> {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.setScreen(new HudAnchorEditorScreen(minecraft.screen, view));
            });
            this.hudHost = hudHost;
            LiveGameContext gameContextPort = new LiveGameContext(languageSnapshot, diagnosticRouter);
            WorldIdentityCapture worldIdentityCapture = new WorldIdentityCapture(gameDirectory);
            TianshuRuntimePorts.register(WorldFactsSource.class, worldIdentityCapture::current);

            audioManager = new AudioManager(diagnosticRouter, diagnosticRouter);
            session.own(audioManager::shutdown);

            MainThreadScheduler clientScheduler = new MainThreadScheduler();
            SwingFilePicker clientFilePicker = new SwingFilePicker(clientTexts);
            session.own(clientFilePicker::close);
            UiHost clientUiHost = new UiHost(() -> settingsModule);
            NativeModelLoadQueue loadQueue = new NativeModelLoadQueue();
            moduleSettingsSources = new ModuleSettingsSourceRegistry();
            // The action channel belongs to the entry point (the network registration needs it before any world
            // connects); the port only exposes it to modules.
            com.rheinmetal.tianshu.mc.host.ServerActionClient actions = TianshuFabric.actionClient();
            ClientPorts clientPorts = new ClientPorts(
                    clientScheduler,
                    clientUiHost,
                    clientTexts,
                    clientFilePicker,
                    this::openSettingsLayer,
                    audioManager instanceof com.rheinmetal.tianshu.client.host.ClientAudioDeviceCatalog catalog ? catalog : null,
                    moduleConfigs,
                    loadQueue,
                    hudHost,
                    gameContextPort,
                    config::isDebugEnabled,
                    chatCatalogue,
                    new LocalPresentation(),
                    actions
            );
            coreManager = new TianshuCoreManager(environment, audioManager, clientPorts,
                    context -> new ClientTianshuModuleAssembler(
                            createModuleProviders(context, loadQueue),
                            context,
                            moduleSettingsSources));
            TianshuCoreManager assembledCoreManager = coreManager;
            session.own(() -> assembledCoreManager.destroy().join());

            TianshuClientRuntime clientRuntime = new TianshuClientRuntime(
                    new ClientRuntimeServices(coreManager, audioManager, diagnosticRouter),
                    () -> diagnosticRouter.info("fabric.world.session_started"),
                    failure -> diagnosticRouter.error("fabric.world.session_lifecycle_failed", failure)
            );
            lifecycleAdapter = new ClientLifecycleAdapter(clientRuntime);
            ClientLifecycleAdapter currentLifecycleAdapter = lifecycleAdapter;
            session.own(currentLifecycleAdapter::onClientShutdown);
            currentLifecycleAdapter.onClientReady();
            session.own(worldIdentityCapture::clear);

            WorldEventPublisher worldEventPublisher = new WorldEventPublisher() {
                @Override
                public void publish(com.rheinmetal.tianshu.protocol.payload.WorldEventPayload event) {
                    coreManager.publishWorldEvent(event);
                }

                @Override
                public boolean hasSubscribers() {
                    return coreManager.hasWorldEventSubscribers();
                }
            };
            WorldEventHooks.bind(worldEventPublisher, diagnosticRouter);
            session.own(() -> WorldEventHooks.clear(worldEventPublisher));

            externalSettingsContributors = new TianshuSettingsContributorRegistry();
            CoreBackedTianshuIntegrationApi integrationApi = new CoreBackedTianshuIntegrationApi(coreManager);
            TianshuIntegrationAccess.publish(integrationApi);
            session.own(() -> TianshuIntegrationAccess.clear(integrationApi));
            settingsModule = new TianshuSettingsModule(createSettingsRegistrySource(clientPorts), config, diagnosticRouter);

            events = new ClientEvents(
                    coreManager,
                    settingsModule,
                    lifecycleAdapter,
                    hudHost,
                    worldIdentityCapture,
                    () -> voiceKey,
                    () -> textInputKey,
                    keyBindings,
                    clientUiHost,
                    actions,
                    () -> TianshuFabric.PROTOCOL_VERSION,
                    diagnosticRouter
            );
            FabricClientEvents.register(events);
            ClientEvents currentEvents = events;
            session.own(currentEvents::resetVoiceInputState);

            registerShutdownHook();
            started = true;
        } catch (RuntimeException | Error failure) {
            try {
                session.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            clearSessionReferences();
            throw failure;
        }
    }

    /**
     * Creates the two keys the host owns and registers every key a module declared.
     *
     * <p>Called from the client initializer, and it has to stay callable exactly once: a second registration
     * would hand the same {@link KeyMapping} to the loader twice.</p>
     */
    public synchronized void registerKeyMappings() {
        voiceKey = HostKeys.voice();
        textInputKey = HostKeys.textInput();
        net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(voiceKey);
        net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(textInputKey);
        keyBindings.registerAll(net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper::registerKeyBinding);
    }

    /** A module's settings row can send the player to the controls screen; the binding itself stays in the host. */
    public DeclaredKeyBindings keyBindings() {
        return keyBindings;
    }

    public com.rheinmetal.tianshu.mc.event.NamedObjectReloadListener reloadListener() {
        return new NamedObjectReloadListener(
                this::onResourcesReloaded,
                diagnosticRouter == null ? LogSink.NOOP : diagnosticRouter
        );
    }

    /** A reload changes translations and resources, so everything measured or resolved from them must be dropped. */
    public void onResourcesReloaded() {
        refreshNamedObjectSnapshot();
        HudLayerHost host = hudHost;
        if (host != null) {
            host.onResourcesReloaded();
        }
        FabricShaders.reload(diagnosticRouter);
    }

    /** The HUD programs features declared; the platform owns the {@code ShaderInstance} lifecycle. */
    public static Set<HudProgramId> declaredPrograms() {
        return new HashSet<>(HudShaderCatalog.declarations().keySet());
    }

    public synchronized void shutdown() {
        if (!started) {
            return;
        }
        started = false;
        LogSink currentLogSink = diagnosticRouter == null ? LogSink.NOOP : diagnosticRouter;
        currentLogSink.info("fabric.client.shutdown_starting");
        try {
            session.close();
        } catch (RuntimeException failure) {
            currentLogSink.error("fabric.client.shutdown_failed", failure);
        } finally {
            clearSessionReferences();
        }
    }

    /** The modules that are part of this session, read at call time rather than from a literal list. */
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
        ids.add(com.rheinmetal.tianshu.protocol.ProtocolModuleIds.PLATFORM_AUDIO);
        ids.add(com.rheinmetal.tianshu.protocol.ProtocolModuleIds.PLATFORM_ONNX_RUNTIME);
        return ids;
    }

    private void clearSessionReferences() {
        SignalFailures.setHandler(null);
        HudShaderPrograms.clear();
        events = null;
        lifecycleAdapter = null;
        hudHost = null;
        coreManager = null;
        audioManager = null;
        diagnosticRouter = null;
        settingsModule = null;
        externalSettingsContributors = null;
    }

    /**
     * The settings layer opens over the page and closing goes back to it.
     *
     * <p>Fabric replaces the current screen instead of stacking one, so the layer draws the page underneath
     * itself; that flag is the only difference from the NeoForge form.</p>
     */
    private void openSettingsLayer(com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerModel layer) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TianshuSettingsScreen settingsScreen) {
            minecraft.setScreen(new SettingsLayerScreen(settingsScreen, layer, true));
        } else {
            layer.onClosed().run();
        }
    }

    private List<TianshuModuleProvider> createModuleProviders(
            com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext context,
            NativeModelLoadQueue loadQueue) {
        return List.of(registrar -> registrar.addModule(new ClientOnnxRuntimeModuleInstaller()));
    }

    private TianshuSettingsRegistrySource createSettingsRegistrySource(ClientPorts ports) {
        TianshuSettingsRegistrySource moduleSource = new ModuleSettingsRegistrySource(coreManager::managedModules);
        TianshuSettingsRegistrySource externalSource = new ExternalSettingsRegistrySource(externalSettingsContributors);
        return CompositeSettingsRegistrySource.of(moduleSource, externalSource, moduleSettingsSources);
    }

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
            languageSnapshot.update(com.rheinmetal.tianshu.mc.host.ClientLanguagePolicy.captureCurrentLanguageCode());
            namedObjectDictionaryProvider.refresh();
        } catch (RuntimeException failure) {
            if (diagnosticRouter != null) {
                diagnosticRouter.error("fabric.ir.dictionary_refresh_failed", failure);
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
                diagnosticRouter.info("fabric.jvm.shutdown_fallback");
            }
            shutdown();
        }, "Tianshu-Shutdown-Hook"));
    }
}
