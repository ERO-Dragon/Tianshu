package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.presence.settings.PresenceSettingsRegistrySource;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * What presence contributes to one client session.
 *
 * <p>Built per session from the platform's ports, never from the host itself: the game context, the text provider, the
 * config stores, the HUD host and the debug switch are all api. A headless or dedicated-server assembly has no client
 * ports, and presence then contributes nothing at all — which is the correct behaviour for a client-only feature.</p>
 *
 * <p>The world session is opened here, while providers contribute, because that happens before any module's
 * {@code register()} runs: presence is then already subscribed when the speech modules publish their first loading
 * state. The module closes the session again when it stops.</p>
 */
public final class PresenceModuleProvider implements TianshuModuleProvider {
    private final TianshuModuleAssemblyContext context;

    public PresenceModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        ClientPorts ports = context.clientPorts();
        if (ports == null
                || ports.hud() == null
                || ports.gameContext() == null
                || ports.configStores() == null
                || ports.texts() == null
                || context.moduleRuntime() == null) {
            return;
        }
        PresenceModuleConfig config = PresenceModuleConfig.create(ports.configStores());
        PresenceTextProvider texts = PresenceTextProvider.of(ports.texts());
        PresenceClientRuntime runtime = new PresenceClientRuntime(ports.gameContext(), texts, logs());
        // Presence owns its part of the routing table: the topics are registered here, while the session is being
        // assembled, so they exist before any module (including this one) publishes its first activity.
        PresenceProtocolAdapter protocol = new PresenceProtocolAdapter(context.moduleRuntime());
        protocol.registerOwnedTopics(context.moduleRuntime());
        runtime.startWorldSession();
        registrar.addModule(runtime.moduleInstaller(protocol, ports.hud(), config));
        registrar.addSettings(new PresenceSettingsRegistrySource(
                config,
                context.services(),
                texts,
                debugEnabled(ports),
                ports.hud().anchorEditor()
        ));
    }

    private LogSink logs() {
        return context.env() == null ? LogSink.NOOP : context.env().logs();
    }

    private static BooleanSupplier debugEnabled(ClientPorts ports) {
        return ports.debugEnabled() == null ? () -> false : ports.debugEnabled();
    }
}
