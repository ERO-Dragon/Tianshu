package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.LlmProtocolAdapter;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.client.host.DiagnosticChatNotice;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfig;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingSettingsRegistrySource;

import java.util.List;

/**
 * Weaving announces itself to the kernel.
 *
 * <p>Registered from the mod entrypoint, so the kernel never names this module: config, installer and
 * settings page are all built here from the ports the kernel offers. Remove this jar and nothing in the
 * kernel changes.</p>
 */
public final class WeavingModuleProvider implements TianshuModuleProvider {

    private final TianshuModuleAssemblyContext context;

    public WeavingModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = context;
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        if (context == null || !context.hasClientPorts()) {
            return;
        }
        ClientPorts ports = context.clientPorts();
        // The wording of this module's progress lines belongs here; the host only looks the codes up.
        ports.diagnosticChat().register("CHAT_COMPLETED", new DiagnosticChatNotice(
                "tianshu.chat.progress.llm.reply", List.of(List.of("input"), List.of("output"))));
        ports.diagnosticChat().register("STREAM_COMPLETED", new DiagnosticChatNotice(
                "tianshu.chat.progress.llm.reply", List.of(List.of("input"), List.of("output"))));
        WeavingConfig config = new WeavingConfig(
                ports.configStores().forModule(LlmProtocolAdapter.MODULE_ID),
                ports.configStores().moduleDirectory(),
                ports.texts()::currentLanguage);

        registrar.addModule(new LlmModuleInstaller(
                context.env(),
                config,
                context.moduleRuntime(),
                ports.modelLoadQueue()));
        registrar.addSettings(new WeavingSettingsRegistrySource(
                context.services(),
                config,
                ports.scheduler(),
                ports.uiHost(),
                ports.settingsLayers()));
    }
}
