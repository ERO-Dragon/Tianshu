package com.rheinmetal.tianshu.neoforge.bootstrap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.audio.AudioManager;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticPolicy;
import com.rheinmetal.tianshu.client.diagnostics.ClientDiagnosticRouter;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import com.rheinmetal.tianshu.client.llm.performance.ClientLlmRuntimeBridge;
import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.client.runtime.ClientRuntimeServices;
import com.rheinmetal.tianshu.client.runtime.TianshuClientRuntime;
import com.rheinmetal.tianshu.client.runtime.module.ClientOnnxRuntimeModuleInstaller;
import com.rheinmetal.tianshu.client.runtime.module.ClientTianshuModuleAssembler;
import com.rheinmetal.tianshu.client.settings.module.asr.AsrSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.ax.AXSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.diagnostics.InternalModuleDiagnosticsSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.llm.LlmSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.module.tts.TtsSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ExternalSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsContributorRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.TianshuFunctionConfigurations;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.integration.CoreBackedTianshuIntegrationApi;
import com.rheinmetal.tianshu.integration.TianshuIntegrationAccess;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeAXWorldIdentityProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientFilePicker;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientScheduler;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientTextProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientUiHost;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeEnvironment;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeNamedObjectDictionaryProvider;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgePresencePlatform;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgePresenceTextProvider;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.config.ClientConfigPresenceHudSettings;
import com.rheinmetal.tianshu.neoforge.event.NamedObjectReloadListener;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientEvents;
import com.rheinmetal.tianshu.neoforge.event.NeoForgeClientLifecycleAdapter;
import com.rheinmetal.tianshu.neoforge.event.NeoForgePresenceHooks;
import com.rheinmetal.tianshu.neoforge.integration.TianshuIntegrationRegisterEvent;
import com.rheinmetal.tianshu.neoforge.ui.hud.PresenceHudRenderer;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.List;

public final class NeoForgeClientBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ClientConfig config = new ClientConfig();
    private KeyMapping voiceKey;
    private AudioManager audioManager;
    private TianshuCoreManager coreManager;
    private TianshuSettingsModule settingsModule;
    private TianshuSettingsContributorRegistry externalSettingsContributors;
    private CoreBackedTianshuIntegrationApi integrationApi;
    private ClientDiagnosticRouter diagnosticRouter;
    private ClientNamedObjectIndexManager namedObjectIndexManager;
    private NeoForgeClientLifecycleAdapter lifecycleAdapter;
    private PresenceClientRuntime presenceRuntime;
    private NeoForgeClientEvents events;
    private boolean started;
    private boolean shutdownHookRegistered;

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        LOGGER.info("天枢 AI 客户端事件开始注册...");

        NeoForgeEnvironment environment = new NeoForgeEnvironment();
        namedObjectIndexManager = createNamedObjectIndexManager();
        diagnosticRouter = new ClientDiagnosticRouter(Minecraft.getInstance().gameDirectory.toPath(), new ClientDiagnosticPolicy(config));
        environment.bindDiagnostics(diagnosticRouter);
        presenceRuntime = new PresenceClientRuntime(new NeoForgePresencePlatform(), new NeoForgePresenceTextProvider());
        PresenceHudRenderer presenceHudRenderer = new PresenceHudRenderer(
                presenceRuntime::currentHudDisplay,
                new ClientConfigPresenceHudSettings(config)
        );
        NeoForgePresenceHooks.bind(presenceRuntime);

        audioManager = new AudioManager();
        String selectedMicName = config.getSelectedMicName();
        if (selectedMicName != null && !selectedMicName.isBlank()) {
            audioManager.selectMic(selectedMicName);
        }

        coreManager = new TianshuCoreManager(environment, config, audioManager, context -> new ClientTianshuModuleAssembler(
                context.env(),
                new TianshuFunctionConfigurations(config, config, config, config),
                context.audioBridge(),
                context.moduleRuntime(),
                namedObjectIndexManager,
                ClientLanguagePolicy::currentPromptLanguage,
                context.voiceInputGate(),
                context.interruptionSignal(),
                new NeoForgeAXWorldIdentityProvider(),
                config,
                AXChatOutputSink.NOOP,
                List.of(
                        new ClientOnnxRuntimeModuleInstaller(),
                        presenceRuntime.moduleInstaller(context.moduleRuntime())
                )
        ));

        TianshuClientRuntime clientRuntime = new TianshuClientRuntime(
                new ClientRuntimeServices(coreManager, audioManager, diagnosticRouter, presenceRuntime, namedObjectIndexManager),
                () -> {
                    ClientLlmRuntimeBridge.bind(coreManager, config);
                    LOGGER.info("天枢世界会话已启动");
                },
                failure -> LOGGER.error("天枢世界会话生命周期失败", failure)
        );
        lifecycleAdapter = new NeoForgeClientLifecycleAdapter(clientRuntime);
        externalSettingsContributors = new TianshuSettingsContributorRegistry();
        integrationApi = new CoreBackedTianshuIntegrationApi(coreManager);
        TianshuIntegrationAccess.publish(integrationApi);
        NeoForge.EVENT_BUS.post(new TianshuIntegrationRegisterEvent(integrationApi, externalSettingsContributors));
        settingsModule = new TianshuSettingsModule(coreManager, createSettingsRegistrySource());

        events = new NeoForgeClientEvents(
                config,
                coreManager,
                settingsModule,
                lifecycleAdapter,
                presenceRuntime,
                presenceHudRenderer,
                () -> voiceKey
        );
        events.register(NeoForge.EVENT_BUS);
        lifecycleAdapter.onClientReady();
        registerShutdownHook();
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
        event.registerReloadListener(new NamedObjectReloadListener(createNamedObjectIndexManager()));
    }

    public synchronized void shutdown() {
        if (!started) {
            return;
        }
        started = false;
        LOGGER.info("关闭天枢客户端资源");
        ClientLlmRuntimeBridge.close();
        PresenceClientRuntime previousPresenceRuntime = presenceRuntime;
        presenceRuntime = null;
        NeoForgePresenceHooks.clear(previousPresenceRuntime);
        if (events != null) {
            events.resetVoiceInputState();
            events = null;
        }
        if (integrationApi != null) {
            TianshuIntegrationAccess.clear(integrationApi);
            integrationApi = null;
        }
        if (lifecycleAdapter != null) {
            NeoForgeClientLifecycleAdapter currentAdapter = lifecycleAdapter;
            lifecycleAdapter = null;
            currentAdapter.onClientShutdown();
        }
        coreManager = null;
        audioManager = null;
        diagnosticRouter = null;
        namedObjectIndexManager = null;
        settingsModule = null;
        externalSettingsContributors = null;
        LOGGER.info("天枢客户端资源清理完成");
    }

    private TianshuSettingsRegistrySource createSettingsRegistrySource() {
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
                coreManager, config, scheduler, uiHost, new NeoForgeClientFilePicker(textProvider), textProvider
        );
        TianshuSettingsRegistrySource llmSource = new LlmSettingsRegistrySource(coreManager, config, scheduler, uiHost);
        TianshuSettingsRegistrySource axSource = new AXSettingsRegistrySource(coreManager, config);
        TianshuSettingsRegistrySource presenceSource = new PresenceSettingsRegistrySource(config, coreManager, presenceTextProvider);
        TianshuSettingsRegistrySource diagnosticsSource = new InternalModuleDiagnosticsSettingsRegistrySource(config);
        return CompositeSettingsRegistrySource.of(
                moduleSource,
                externalSource,
                asrSource,
                llmSource,
                ttsSource,
                axSource,
                diagnosticsSource,
                presenceSource
        );
    }

    private ClientNamedObjectIndexManager createNamedObjectIndexManager() {
        if (namedObjectIndexManager == null) {
            namedObjectIndexManager = new ClientNamedObjectIndexManager(
                    new NeoForgeNamedObjectDictionaryProvider(),
                    Minecraft.getInstance().gameDirectory.toPath()
                            .resolve("config")
                            .resolve("Tianshu")
                            .resolve("module")
                            .resolve("ir")
                            .resolve("cache"),
                    () -> ClientLanguagePolicy.currentPromptLanguage().code()
            );
        }
        return namedObjectIndexManager;
    }

    private void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("检测到 JVM 即将关闭，执行最终清理...");
            shutdown();
        }, "Tianshu-Shutdown-Hook"));
    }
}
