package com.rheinmetal.tianshu.api.game;

/**
 * Where a module reads the platform's current world facts.
 *
 * <p>The platform captures them on the client thread and publishes this; a module decides what the facts
 * mean for it. An absent source is normal in headless assembly, so callers must handle
 * {@link WorldFacts#UNKNOWN}.</p>
 */
@FunctionalInterface
public interface WorldFactsSource {

    WorldFacts current();
}
