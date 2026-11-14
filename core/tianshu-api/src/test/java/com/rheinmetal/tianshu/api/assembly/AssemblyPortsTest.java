package com.rheinmetal.tianshu.api.assembly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ports an optional integration depends on: who is installed, and where a module puts an
 * implementation before any session exists.
 *
 * <p>Both are static process-wide state, so what matters most is the reading when nobody registered
 * anything. A module asks "is my partner here?" long before any {@code @Mod} constructor has run in a
 * unit test; if the empty answer were "present" every optional integration would try to load classes
 * that are not on the classpath.</p>
 */
class AssemblyPortsTest {

    private interface SamplePort {
        String value();
    }

    @BeforeEach
    @AfterEach
    void resetPorts() {
        TianshuRuntimePorts.clear();
    }

    @Test
    void theUnregisteredReadingOfInstalledModsIsAbsent() {
        InstalledMods installed = InstalledMods.none();

        assertFalse(installed.isLoaded("module.tts"), "an unknown partner must read as absent");
        assertFalse(installed.isLoaded("tianshu_echo"));
        assertFalse(installed.isLoaded(null), "a missing id is not an installed mod");
    }

    @Test
    void anEmptyPortRegistryFindsNothing() {
        assertTrue(TianshuRuntimePorts.find(SamplePort.class).isEmpty());
    }

    @Test
    void aRegisteredPortIsFoundByItsInterface() {
        SamplePort implementation = () -> "loaded";
        TianshuRuntimePorts.register(SamplePort.class, implementation);

        Optional<SamplePort> found = TianshuRuntimePorts.find(SamplePort.class);

        assertTrue(found.isPresent());
        assertSame(implementation, found.get(), "lookup returns the registered instance, not a copy");
    }

    @Test
    void portsAreKeyedByInterfaceSoTwoOfThemDoNotCollide() {
        TianshuRuntimePorts.register(SamplePort.class, () -> "first");
        TianshuRuntimePorts.register(Runnable.class, () -> {
        });

        assertEquals("first", TianshuRuntimePorts.find(SamplePort.class).orElseThrow().value());
        assertTrue(TianshuRuntimePorts.find(Runnable.class).isPresent());
    }

    @Test
    void registeringAgainReplacesTheEarlierImplementation() {
        TianshuRuntimePorts.register(SamplePort.class, () -> "first");
        TianshuRuntimePorts.register(SamplePort.class, () -> "second");

        assertEquals("second", TianshuRuntimePorts.find(SamplePort.class).orElseThrow().value());
    }

    @Test
    void nullRegistrationIsIgnoredInsteadOfStoringABlank() {
        SamplePort absent = null;
        TianshuRuntimePorts.register(SamplePort.class, absent);
        TianshuRuntimePorts.register((Class<SamplePort>) null, () -> "value");

        assertTrue(TianshuRuntimePorts.find(SamplePort.class).isEmpty());
        assertTrue(TianshuRuntimePorts.find((Class<SamplePort>) null).isEmpty());
    }

    @Test
    void clearDropsEveryRegistration() {
        TianshuRuntimePorts.register(SamplePort.class, () -> "value");

        TianshuRuntimePorts.clear();

        assertTrue(TianshuRuntimePorts.find(SamplePort.class).isEmpty(),
                "a test that registers a port must be able to put the process back");
    }
}
