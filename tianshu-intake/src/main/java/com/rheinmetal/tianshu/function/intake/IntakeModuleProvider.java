package com.rheinmetal.tianshu.function.intake;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.NamedObjectDictionary;
import com.rheinmetal.tianshu.api.game.NamedObjectIndex;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.intake.index.ClientIrNamedObjectEnhancer;
import com.rheinmetal.tianshu.function.intake.index.ClientNamedObjectIndexManager;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerConfig;
import com.rheinmetal.tianshu.function.intake.settings.IntakeConfig;
import com.rheinmetal.tianshu.function.intake.settings.IntakeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;

import java.nio.file.Path;

/**
 * Intake announces itself to the kernel.
 *
 * <p>It also owns the named-object index: the platform only supplies the dictionary snapshot, and this
 * module decides where the index caches, when it is built and when it is released. The kernel can observe
 * it through {@link NamedObjectIndex} but never drives it, so removing this jar removes the index too.</p>
 */
public final class IntakeModuleProvider implements TianshuModuleProvider {

    private static final String INDEX_REASON = "intake.assembly";

    private final TianshuModuleAssemblyContext context;

    public IntakeModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = context;
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        if (context == null || !context.hasClientPorts()) {
            return;
        }
        ClientPorts ports = context.clientPorts();
        Path moduleDirectory = ports.configStores().moduleDirectory();
        IntakeConfig config = new IntakeConfig(
                ports.configStores().forModule(ProtocolModuleIds.ASR),
                moduleDirectory,
                ports.texts()::currentLanguage);

        String selectedMic = config.getSelectedMicName();
        if (selectedMic != null && !selectedMic.isBlank() && ports.audioDevices() != null) {
            ports.audioDevices().selectMic(selectedMic);
        }

        registrar.addModule(new AsrModuleInstaller(
                context.audioBridge(),
                context.moduleRuntime(),
                context.env(),
                config,
                context.voiceInputGate(),
                context.interruptionSignal(),
                ports.modelLoadQueue(),
                new ClientIrNamedObjectEnhancer(index(ports, moduleDirectory, context.env().logs()))));
        registrar.addSettings(new IntakeSettingsRegistrySource(
                context.services(),
                config,
                context.audioBridge(),
                ports.scheduler(),
                ports.uiHost(),
                ports.texts(),
                ports.audioDevices(),
                () -> UiText.key("key.tianshu.activate"),
                new IaDefaultOwnerConfig(ports.configStores().forModule(ProtocolModuleIds.IA))));
    }

    /**
     * One index per process, built lazily on first assembly.
     *
     * <p>It is registered under {@link NamedObjectIndex} so the kernel can drive nothing and observe
     * everything: a diagnostics view can ask it to reload, and an absent intake jar simply leaves the port
     * unregistered.</p>
     */
    private static ClientNamedObjectIndexManager index(ClientPorts ports, Path moduleDirectory, LogSink logs) {
        synchronized (IntakeModuleProvider.class) {
            return TianshuRuntimePorts.find(ClientNamedObjectIndexManager.class).orElseGet(() -> {
                NamedObjectDictionary dictionary = TianshuRuntimePorts.find(NamedObjectDictionary.class)
                        .orElse(null);
                ClientNamedObjectIndexManager created = new ClientNamedObjectIndexManager(
                        dictionary,
                        moduleDirectory.resolve("asr").resolve("index"),
                        ports.texts()::currentLanguage,
                        logs);
                created.initializeAsync(INDEX_REASON);
                TianshuRuntimePorts.register(NamedObjectIndex.class, created);
                TianshuRuntimePorts.register(ClientNamedObjectIndexManager.class, created);
                return created;
            });
        }
    }
}
