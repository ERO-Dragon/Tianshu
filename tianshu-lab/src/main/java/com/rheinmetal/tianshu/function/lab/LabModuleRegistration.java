package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;

/**
 * What this jar announces to whichever loader is starting.
 *
 * <p>One provider, no ports: the bench owns no runtime service and no keyword table. The loader shell calls
 * this once; the provider is what the session later asks for modules.</p>
 */
public final class LabModuleRegistration {

    private LabModuleRegistration() {
    }

    public static void announce() {
        TianshuModuleProviderRegistry.register(LabModuleProvider::new);
    }
}