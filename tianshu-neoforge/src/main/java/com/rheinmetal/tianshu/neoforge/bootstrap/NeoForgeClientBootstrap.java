package com.rheinmetal.tianshu.neoforge.bootstrap;

import com.mojang.blaze3d.platform.InputConstants;
import com.rheinmetal.tianshu.client.audio.AudioManager;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticPolicy;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticRouter;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticMessageSink;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import com.rheinmetal.tianshu.client.llm.performance.ClientLlmRuntimeBridge;
import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.client.runtime.ClientRuntimeServices;
import com.rheinmetal.tianshu.client.runtime.TianshuClientRuntime;
import com.rheinmetal.tianshu.client.runtime.module.ClientOnnxRuntimeModuleInstaller;
import com.rheinmetal.tianshu.client.runtime.module.ClientTianshuModuleAssembler;
import com.rheinmetal.tianshu.client.settings.module.asr.AsrSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.ax.AXSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.llm.LlmSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.tts.TtsSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ExternalSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsContributorRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.client.runtime.module.ClientFunctionConfigurations;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.integration.CoreBackedTianshuIntegrationApi;
import com.rheinmetal.tianshu.integration.TianshuIntegrationAccess;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguageSnapshot;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeAXWorldIdentityProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientFilePicker;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientScheduler;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientTextProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientUiHost;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeEnvironment;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeNamedObjectDictionaryProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgePresencePlatform;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgePresenceTextProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeWorldIdentityCapture;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.config.ClientConfigPresenceHudSettings;
import com.rheinmetal.tianshu.neoforge.event.NamedObjectReloadListener;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientEvents;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientLifecycleAdapter;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeEventRegistration;
import com.rheinmetal.tianshu.neoforge.event.NeoForgePresenceHooks;
import com.rheinmetal.tianshu.neoforge.integration.TianshuIntegrationRegisterEvent;
import com.rheinmetal.tianshu.neoforge.ui.hud.PresenceHudRenderer;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class NeoForgeClientBootstrap {
    private final ClientLanguageSnapshot languageSnapshot = new ClientLanguageSnapshot();
    private final Path gameDirectory;
    private final ClientConfig config;
    private KeyMapping voiceKey;
    private AudioManager audioManager;
    private TianshuCoreManager coreManager;
    private TianshuSettingsModule settingsModule;
    private TianshuSettingsContributorRegistry externalSettingsContributors;
    private CoreBackedTianshuIntegrationApi integrationApi;
    private ClientDiagnosticRouter diagnosticRouter;
    private ClientNamedObjectIndexManager namedObjectIndexManager;
    private NeoForgeNamedObjectDictionaryProvider namedObjectDictionaryProvider;
    private NeoForgeClientLifecycleAdapter lifecycleAdapter;
    private PresenceClientRuntime presenceRuntime;
    private NeoForgeClientEvents events;
    private NeoForgeClientSession clientSession;
    private boolean shutdownHookRegistered;

    public NeoForgeClientBootstrap() {
        this(FMLPaths.GAMEDIR.get());
    }

    NeoForgeClientBootstrap(Path gameDirectory) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
        this.config = new ClientConfig(gameDirectory, languageSnapshot::languageCode);
    }

    public synchronized void start() {
        if (clientSession != null) {
            return;
        }
        NeoForgeClientSession startingSession = new NeoForgeClientSession();
        try {
            NeoForgeEnvironment environment = new NeoForgeEnvironment(gameDirectory);
            ClientDiagnosticMessageSink chatSink = message -> Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null && message != null && !message.isBlank()) {
                    Minecraft.getInstance().player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(message), false
                    );
                }
            });
            diagnosticRouter = new ClientDiagnosticRouter(
                    gameDirectory,
                    new ClientDiagnosticPolicy(config),
                    2_048,
                    8L * 1024L * 1024L,
                    5,
                    chatSink
            );
            NeoForgeClientSession.Ownership diagnosticsOwnership = startingSession.own(diagnosticRouter::close);
            environment.bindDiagnostics(diagnosticRouter);
            environment.bindLogs(diagnosticRouter);
            diagnosticRouter.info("neoforge.client.bootstrap_starting");
            namedObjectIndexManager = createNamedObjectIndexManager();
            NeoForgeClientSession.Ownership indexOwnership = startingSession.own(namedObjectIndexManager::close);
            refreshNamedObjectSnapshot();
            presenceRuntime = new PresenceClientRuntime(new NeoForgePresencePlatform(languageSnapshot, diagnosticRouter), new NeoForgePresenceTextProvider());
            PresenceClientRuntime currentPresenceRuntime = presenceRuntime;
            ClientConfigPresenceHudSettings presenceHudSettings = new ClientConfigPresenceHudSettings(config);
            PresenceHudRenderer presenceHudRenderer = new PresenceHudRenderer(
                    currentPresenceRuntime::currentHudDisplay,
                    presenceHudSettings
            );
            NeoForgeAXWorldIdentityProvider worldIdentityProvider = new NeoForgeAXWorldIdentityProvider();
            NeoForgeWorldIdentityCapture worldIdentityCapture = new NeoForgeWorldIdentityCapture(worldIdentityProvider, gameDirectory);

            audioManager = new AudioManager(diagnosticRouter);
            NeoForgeClientSession.Ownership audioOwnership = startingSession.own(audioManager::shutdown);
            String selectedMicName = config.getSelectedMicName();
            if (selectedMicName != null && !selectedMicName.isBlank()) {
                audioManager.selectMic(selectedMicName);
            }

            coreManager = new TianshuCoreManager(environment, config, audioManager, context -> new ClientTianshuModuleAssembler(
                    context.env(),
                    new ClientFunctionConfigurations(config, config, config, config),
                    context.audioBridge(),
                    context.moduleRuntime(),
                    namedObjectIndexManager,
                    languageSnapshot::promptLanguage,
                    context.voiceInputGate(),
                    context.interruptionSignal(),
                    worldIdentityProvider,
                    config,
                    AXChatOutputSink.NOOP,
                    List.of(
                            new ClientOnnxRuntimeModuleInstaller(),
                            presenceRuntime.moduleInstaller(context.moduleRuntime())
                    )
            ));
            TianshuCoreManager assembledCoreManager = coreManager;
            NeoForgeClientSession.Ownership coreOwnership = startingSession.own(
                    () -> assembledCoreManager.destroy().join()
            );

            TianshuCoreManager currentCoreManager = coreManager;
            TianshuClientRuntime clientRuntime = new TianshuClientRuntime(
                    new ClientRuntimeServices(coreManager, audioManager, diagnosticRouter, presenceRuntime, namedObjectIndexManager),
                    () -> {
                        ClientLlmRuntimeBridge.bind(currentCoreManager, config);
                        diagnosticRouter.info("neoforge.world.session_started");
                    },
                    failure -> diagnosticRouter.error("neoforge.world.session_lifecycle_failed", failure)
            );
            lifecycleAdapter = new NeoForgeClientLifecycleAdapter(clientRuntime);
            NeoForgeClientLifecycleAdapter currentLifecycleAdapter = lifecycleAdapter;
            startingSession.own(currentLifecycleAdapter::onClientShutdown);
            coreOwnership.release();
            audioOwnership.release();
            diagnosticsOwnership.release();
            indexOwnership.release();
            startingSession.own(ClientLlmRuntimeBridge::close);
            currentLifecycleAdapter.onClientReady();
            startingSession.own(worldIdentityCapture::clear);

            NeoForgePresenceHooks.bind(currentPresenceRuntime, diagnosticRouter);
            startingSession.own(() -> NeoForgePresenceHooks.clear(currentPresenceRuntime));

            externalSettingsContributors = new TianshuSettingsContributorRegistry();
            integrationApi = new CoreBackedTianshuIntegrationApi(coreManager);
            CoreBackedTianshuIntegrationApi currentIntegrationApi = integrationApi;
            TianshuIntegrationAccess.publish(currentIntegrationApi);
            startingSession.own(() -> TianshuIntegrationAccess.clear(currentIntegrationApi));
            NeoForgeClientFilePicker filePicker = new NeoForgeClientFilePicker(new NeoForgeClientTextProvider());
            startingSession.own(filePicker::close);
            settingsModule = new TianshuSettingsModule(createSettingsRegistrySource(filePicker), config, diagnosticRouter);
            NeoForge.EVENT_BUS.post(new TianshuIntegrationRegisterEvent(currentIntegrationApi, externalSettingsContributors));

            events = new NeoForgeClientEvents(
                    config,
                    coreManager,
                    settingsModule,
                    lifecycleAdapter,
                    presenceRuntime,
                    presenceHudRenderer,
                    worldIdentityCapture,
                    () -> voiceKey,
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

    public synchronized void registerKeyMappings(RegisterKeyMappingsEvent event) {
        voiceKey = new KeyMapping(
                "key.tianshu.activate",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.tianshu"
        );
        event.register(voiceKey);
    }

    public synchronized void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new NamedObjectReloadListener(
                this::createNamedObjectIndexManager,
                this::refreshNamedObjectSnapshot,
                diagnosticRouter == null ? com.rheinmetal.tianshu.api.LogSink.NOOP : diagnosticRouter
        ));
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
        currentLogSink.info("neoforge.client.shutdown_completed");
    }

    private void clearSessionReferences() {
        events = null;
        integrationApi = null;
        lifecycleAdapter = null;
        presenceRuntime = null;
        coreManager = null;
        audioManager = null;
        diagnosticRouter = null;
        namedObjectIndexManager = null;
        settingsModule = null;
        externalSettingsContributors = null;
    }

    private TianshuSettingsRegistrySource createSettingsRegistrySource(NeoForgeClientFilePicker filePicker) {
        TianshuSettingsRegistrySource moduleSource = new ModuleSettingsRegistrySource(coreManager::managedModules);
        TianshuSettingsRegistrySource externalSource = new ExternalSettingsRegistrySource(externalSettingsContributors);
        NeoForgeClientScheduler scheduler = new NeoForgeClientScheduler();
        NeoForgeClientUiHost uiHost = new NeoForgeClientUiHost(() -> settingsModule);
        NeoForgePresenceTextProvider presenceTextProvider = new NeoForgePresenceTextProvider();
        NeoForgeClientTextProvider textProvider = new NeoForgeClientTextProvider();
        TianshuSettingsRegistrySource asrSource = new AsrSettingsRegistrySource(
                coreManager, config, audioManager, scheduler, uiHost, presenceTextProvider
        );
        TianshuSettingsRegistrySource ttsSource = new TtsSettingsRegistrySource(
                coreManager, config, scheduler, uiHost, filePicker, textProvider
        );
        TianshuSettingsRegistrySource llmSource = new LlmSettingsRegistrySource(coreManager, config, scheduler, uiHost);
        TianshuSettingsRegistrySource axSource = new AXSettingsRegistrySource(coreManager, config);
        TianshuSettingsRegistrySource presenceSource = new PresenceSettingsRegistrySource(config, coreManager, presenceTextProvider, config::isDebugEnabled);
        return CompositeSettingsRegistrySource.of(
                moduleSource,
                externalSource,
                asrSource,
                llmSource,
                ttsSource,
                axSource,
                presenceSource
        );
    }

    private synchronized ClientNamedObjectIndexManager createNamedObjectIndexManager() {
        if (namedObjectIndexManager == null) {
            if (namedObjectDictionaryProvider == null) {
                namedObjectDictionaryProvider = new NeoForgeNamedObjectDictionaryProvider(languageSnapshot);
            }
            namedObjectIndexManager = new ClientNamedObjectIndexManager(
                    namedObjectDictionaryProvider,
                    gameDirectory
                            .resolve("config")
                            .resolve("Tianshu")
                            .resolve("module")
                            .resolve("ir")
                            .resolve("cache"),
                    languageSnapshot::languageCode
                    , diagnosticRouter
            );
        }
        return namedObjectIndexManager;
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
