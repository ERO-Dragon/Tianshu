package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.client.host.hud.HudLayer;
import com.rheinmetal.tianshu.api.config.ModuleConfigStores;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.runtime.WorldSessionListener;
import com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsRegistrySource;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.api.client.host.hud.HudAnchorEditor;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The whole of presence, as the composition root sees it.
 *
 * <p>One type, one creation call, and the platform never names presence's runtime, settings file, HUD layer or settings
 * page. That is the point: the host's half is the ports it passes in (game context, text, config stores, the HUD host),
 * and everything below this class is presence's own business — which is exactly what makes the feature extractable.
 *
 * <p>It deliberately exposes {@link TianshuModuleProvider} and the api's {@link HudLayer} rather than their concrete
 * types, so removing presence from a build removes one line here and nothing else.</p>
 */
public final class PresenceClientFeature {
    private final PresenceClientRuntime runtime;
    private final PresenceModuleConfig config;
    private final PresenceTextProvider texts;

    private PresenceClientFeature(PresenceClientRuntime runtime, PresenceModuleConfig config, PresenceTextProvider texts) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.config = Objects.requireNonNull(config, "config");
        this.texts = texts == null ? PresenceTextProvider.NOOP : texts;
    }

    public static PresenceClientFeature create(
            ClientGameContextPort gameContext,
            ClientTextProvider clientTexts,
            ModuleConfigStores configStores,
            LogSink logSink
    ) {
        PresenceTextProvider texts = PresenceTextProvider.of(clientTexts);
        return new PresenceClientFeature(
                new PresenceClientRuntime(gameContext, texts, logSink),
                PresenceModuleConfig.create(configStores),
                texts
        );
    }

    /** The world-session participant the client runtime drives. */
    public WorldSessionListener worldSession() {
        return runtime;
    }

    /** Where the module is installed; an absent feature simply contributes no provider. */
    public TianshuModuleProvider moduleProvider(ModuleRuntimeAccess moduleRuntime) {
        return registrar -> registrar.addModule(runtime.moduleInstaller(moduleRuntime));
    }

    /** The HUD as a platform layer; the platform decides where it is registered. */
    public HudLayer hudLayer() {
        return runtime.hudLayer(config);
    }

    /** Presence's page in the settings registry, built with the platform's editor and debug switch. */
    public PresenceSettingsRegistrySource settingsSource(
            TianshuCoreManager coreManager,
            BooleanSupplier debugEnabled,
            HudAnchorEditor anchorEditor
    ) {
        return new PresenceSettingsRegistrySource(config, coreManager, texts, debugEnabled, anchorEditor);
    }
}
