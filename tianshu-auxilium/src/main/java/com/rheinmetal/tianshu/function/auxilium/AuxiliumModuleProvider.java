package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.WorldFacts;
import com.rheinmetal.tianshu.api.game.WorldFactsSource;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.producer.ModGameEventTexts;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AuxiliumWorldIdentity;
import com.rheinmetal.tianshu.function.auxilium.settings.AuxiliumConfig;
import com.rheinmetal.tianshu.function.auxilium.settings.AuxiliumSettingsRegistrySource;
import com.rheinmetal.tianshu.function.auxilium.speech.AXProtocolSpeechOutlet;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechBridge;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
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
        AuxiliumConfig config = new AuxiliumConfig(
                ports.configStores().forModule(ProtocolModuleIds.AX),
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
                AXChatOutputSink.NOOP,
                speechBridge,
                speechBridge,
                texts
        ));
        registrar.addSettings(new AuxiliumSettingsRegistrySource(context.services(), config));
    }
}
