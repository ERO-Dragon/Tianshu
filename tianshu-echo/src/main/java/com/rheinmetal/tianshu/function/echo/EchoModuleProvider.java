package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.client.host.DiagnosticChatNotice;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfig;
import com.rheinmetal.tianshu.function.echo.settings.EchoSettingsRegistrySource;

/**
 * Echo announces itself to the kernel.
 *
 * <p>Registered from the mod entrypoint, so the kernel never names this module: the settings, the
 * installer and the page are all built here from the ports the kernel offers. Remove this jar and
 * nothing in the kernel changes.</p>
 */
public final class EchoModuleProvider implements TianshuModuleProvider {

    private final TianshuModuleAssemblyContext context;

    public EchoModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = context;
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        if (context == null || !context.hasClientPorts()) {
            // Headless assembly: the module has no settings page and no client services to use.
            return;
        }
        ClientPorts ports = context.clientPorts();
        // The wording of this module's progress lines belongs here; the host only looks the codes up.
        ports.diagnosticChat().register("SYNTHESIS_COMPLETED", DiagnosticChatNotice.single(
                "tianshu.chat.progress.tts.input", "text", "input", "output"));
        EchoConfig config = new EchoConfig(
                ports.configStores().forModule(TtsModuleId.VALUE),
                ports.configStores().moduleDirectory());

        registrar.addModule(new TtsModuleInstaller(
                context.audioBridge(),
                context.moduleRuntime(),
                context.env(),
                config,
                ports.modelLoadQueue()));
        registrar.addSettings(new EchoSettingsRegistrySource(
                context.services(),
                config,
                ports.scheduler(),
                ports.uiHost(),
                ports.filePicker(),
                ports.texts()));
    }
}
