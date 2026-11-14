package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.AXModule;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.WorldFacts;
import com.rheinmetal.tianshu.api.game.WorldFactsSource;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.DiagnosticChatNotice;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumChatOutput;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.producer.ModGameEventTexts;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AuxiliumWorldIdentity;
import com.rheinmetal.tianshu.function.auxilium.settings.AuxiliumConfig;
import com.rheinmetal.tianshu.function.auxilium.settings.AuxiliumSettingsRegistrySource;
import com.rheinmetal.tianshu.function.auxilium.speech.AXProtocolSpeechOutlet;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechBridge;
import com.rheinmetal.tianshu.function.auxilium.bridge.AXSpeechSinks;

/**
 * Auxilium announces itself to the kernel.
 *
 * <p>The platform hands over plain facts — language code, world facts, a text table — and this module
 * turns them into its own vocabulary: prompt language, scope snapshot, rendered event sentences. The
 * kernel never sees those types, so removing this jar leaves nothing behind.</p>
 */
public final class AuxiliumModuleProvider implements TianshuModuleProvider {

    private final TianshuModuleAssemblyContext context;

    public AuxiliumModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = context;
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        if (context == null || !context.hasClientPorts()) {
            return;
        }
        ClientPorts ports = context.clientPorts();
        // The wording of this module's progress lines belongs here; the host only looks the codes up.
        ports.diagnosticChat().register("DIALOGUE_DELIVERY", DiagnosticChatNotice.single(
                "tianshu.chat.progress.ax.input", "normalizedText", "repairedText", "input"));
        // The assistant owns its name, so it also owns what the typed-dialogue bar says.
        ports.uiHost().setTextInputLabels(
                UiText.key("tianshu.gui.ax.text_input.title"),
                UiText.key("tianshu.gui.ax.text_input.hint"));
        AuxiliumConfig config = new AuxiliumConfig(
                ports.configStores().forModule(AXModule.MODULE_ID),
                ports.configStores().moduleDirectory());

        WorldFactsSource worldFacts = TianshuRuntimePorts.find(WorldFactsSource.class)
                .orElse(() -> WorldFacts.UNKNOWN);
        AXWorldIdentityProvider worldIdentity = new AuxiliumWorldIdentity(worldFacts::current);

        // One language door for this session: the speech lines and the event layer both resolve through it,
        // so the provider is created once and shared instead of each path making its own.
        ModGameEventTexts texts = new ModGameEventTexts(ports.texts());
        AXSpeechBridge speechBridge = new AXSpeechBridge(
                new AXProtocolSpeechOutlet(
                        AXSpeechSinks.forRuntime(context.moduleRuntime()),
                        texts,
                        config,
                        context.env().logs()),
                context.env().logs());

        registrar.addModule(new AXModuleInstaller(
                context.env(),
                config,
                context.moduleRuntime(),
                worldIdentity,
                AXPromptLanguageProvider.ofCode(ports.texts()::currentLanguage),
                config,
                AXRuntimePolicy.defaults(),
                config,
                new AuxiliumChatOutput(ports.presentation()),
                speechBridge,
                speechBridge,
                texts
        ));
        registrar.addSettings(new AuxiliumSettingsRegistrySource(context.services(), config));
    }
}
