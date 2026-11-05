package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.TianshuSettingsContributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalSettingsRegistrySourceTest {

    @AfterEach
    void clearDeclarations() {
        ExternalSettingsContributors.resetForTests();
    }

    @Test
    void processDeclarationContributesWithoutASessionRegistry() {
        List<String> applied = new ArrayList<>();
        ExternalSettingsContributors.register(page(applied, "fabric-mod"));

        new ExternalSettingsRegistrySource(null).contribute(new TianshuSettingsRegistry(), null);

        assertEquals(List.of("fabric-mod"), applied);
    }

    @Test
    void sessionAndProcessDeclarationsAreBothApplied() {
        List<String> applied = new ArrayList<>();
        TianshuSettingsContributorRegistry session = new TianshuSettingsContributorRegistry();
        session.register(page(applied, "neoforge-mod"));
        ExternalSettingsContributors.register(page(applied, "fabric-mod"));

        new ExternalSettingsRegistrySource(session).contribute(new TianshuSettingsRegistry(), null);

        assertEquals(2, applied.size());
        assertEquals(List.of("fabric-mod", "neoforge-mod"), applied.stream().sorted().toList());
    }

    @Test
    void aContributorDeclaredInBothPlacesRunsOnce() {
        List<String> applied = new ArrayList<>();
        TianshuSettingsContributor shared = page(applied, "shared");
        TianshuSettingsContributorRegistry session = new TianshuSettingsContributorRegistry();
        session.register(shared);
        ExternalSettingsContributors.register(shared);

        new ExternalSettingsRegistrySource(session).contribute(new TianshuSettingsRegistry(), null);

        assertEquals(List.of("shared"), applied);
    }

    @Test
    void unregisteredProcessDeclarationIsNotApplied() {
        List<String> applied = new ArrayList<>();
        TianshuSettingsContributor page = page(applied, "gone");
        ExternalSettingsContributors.register(page);
        ExternalSettingsContributors.unregister(page);

        new ExternalSettingsRegistrySource(null).contribute(new TianshuSettingsRegistry(), null);

        assertEquals(List.of(), applied);
    }

    private static TianshuSettingsContributor page(List<String> applied, String name) {
        return (registry, context) -> applied.add(name);
    }
}