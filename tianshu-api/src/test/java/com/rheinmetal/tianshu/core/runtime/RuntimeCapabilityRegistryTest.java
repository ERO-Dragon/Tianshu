package com.rheinmetal.tianshu.core.runtime;

import com.rheinmetal.tianshu.api.LogSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A capability belongs to the module that installed it.
 *
 * <p>The rule was written down but not enforced: every writer handed in its own module id and nothing compared it to
 * the existing owner, so a copy-pasted {@code markReady} could clear another module's failure and rewrite the owner.
 * These cases pin the enforcement, including that a refused write is reported rather than dropped.</p>
 */
class RuntimeCapabilityRegistryTest {

    private static final RuntimeCapability CAPABILITY = RuntimeCapability.of("test.capability");

    private final List<String> warnings = new ArrayList<>();

    private final LogSink recorder = new LogSink() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message, Throwable failure) {
            warnings.add(message);
        }
    };

    @Test
    void theFirstInstallerOwnsTheCapability() {
        RuntimeCapabilityRegistry registry = new RuntimeCapabilityRegistry(recorder);

        registry.install(CAPABILITY, "module.a");

        assertEquals("module.a", registry.status(CAPABILITY).ownerModuleId());
        assertTrue(registry.status(CAPABILITY).installed());
    }

    @Test
    void anotherModuleCannotResetTheStateOrTakeOwnership() {
        RuntimeCapabilityRegistry registry = new RuntimeCapabilityRegistry(recorder);
        registry.install(CAPABILITY, "module.a");
        registry.markFailed(CAPABILITY, "module.a", "the model failed to load");

        registry.markReady(CAPABILITY, "module.b");

        assertTrue(registry.status(CAPABILITY).failed(),
                "another module must not report someone else's failure as fixed");
        assertEquals("module.a", registry.status(CAPABILITY).ownerModuleId(), "ownership must not move");
        assertTrue(warnings.stream().anyMatch(line -> line.contains("module.b")
                        && line.contains("test.capability")),
                "the refused write must say who tried: " + warnings);
    }

    @Test
    void theOwnerCanStillChangeItsOwnCapability() {
        RuntimeCapabilityRegistry registry = new RuntimeCapabilityRegistry(recorder);
        registry.install(CAPABILITY, "module.a");
        registry.markFailed(CAPABILITY, "module.a", "first attempt");

        registry.markReady(CAPABILITY, "module.a");

        assertTrue(registry.status(CAPABILITY).ready(), "the owner must not be locked out of its own capability");
        assertTrue(warnings.isEmpty(), "a legitimate write must not be reported: " + warnings);
    }
}