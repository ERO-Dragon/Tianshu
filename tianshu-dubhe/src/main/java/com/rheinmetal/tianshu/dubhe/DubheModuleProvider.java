package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.dubhe.DubheModule;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.WorldFacts;
import com.rheinmetal.tianshu.api.game.WorldFactsSource;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.DiagnosticChatNotice;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.dubhe.core.output.DubheChatOutput;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.producer.ModGameEventTexts;
import com.rheinmetal.tianshu.dubhe.scope.DubheWorldIdentityProvider;
import com.rheinmetal.tianshu.dubhe.scope.DubheWorldIdentity;
import com.rheinmetal.tianshu.dubhe.settings.DubheConfig;
import com.rheinmetal.tianshu.dubhe.settings.DubheSettingsRegistrySource;
import com.rheinmetal.tianshu.dubhe.speech.DubheProtocolSpeechOutlet;
import com.rheinmetal.tianshu.dubhe.speech.DubheSpeechBridge;
import com.rheinmetal.tianshu.dubhe.bridge.DubheSpeechSinks;

/**
 * Dubhe announces itself to the kernel.
 *
 * <p>The platform hands over plain facts — language code, world facts, a text table — and this module
 * turns them into its own vocabulary: prompt language, scope snapshot, rendered event sentences. The
 * kernel never sees those types, so removing this jar leaves nothing behind.</p>
 */
public final class DubheModuleProvider implements TianshuModuleProvider {

    private final TianshuModuleAssemblyContext context;

    public DubheModuleProvider(TianshuModuleAssemblyContext context) {
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
        DubheConfig config = new DubheConfig(
                ports.configStores().forModule(DubheModule.MODULE_ID),
                ports.configStores().moduleDirectory());

        WorldFactsSource worldFacts = TianshuRuntimePorts.find(WorldFactsSource.class)
                .orElse(() -> WorldFacts.UNKNOWN);
        DubheWorldIdentityProvider worldIdentity = new DubheWorldIdentity(worldFacts::current);

        // One language door for this session: the speech lines and the event layer both resolve through it,
        // so the provider is created once and shared instead of each path making its own.
        ModGameEventTexts texts = new ModGameEventTexts(ports.texts());
        DubheSpeechBridge speechBridge = new DubheSpeechBridge(
                new DubheProtocolSpeechOutlet(
                        DubheSpeechSinks.forRuntime(context.moduleRuntime()),
                        texts,
                        config,
                        context.env().logs()),
                context.env().logs());

        registrar.addModule(new DubheModuleInstaller(
                context.env(),
                config,
                context.moduleRuntime(),
                worldIdentity,
                DubhePromptLanguageProvider.ofCode(ports.texts()::currentLanguage),
                config,
                DubheRuntimePolicy.defaults(),
                config,
                new DubheChatOutput(ports.presentation()),
                speechBridge,
                speechBridge,
                texts
        ));
        registrar.addSettings(new DubheSettingsRegistrySource(context.services(), config));
    }
}
