package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.client.tool.BuiltinServerTools;
import com.rheinmetal.tianshu.client.tool.BodyCommandRunTool;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server start owns what it installed, and a stop takes it back.
 *
 * <p>Leaving the ports behind kept the {@code MinecraftServer} reachable from the title screen, and re-registering the
 * action tools on the second world threw on a duplicate id and aborted the rest of the start.</p>
 */
class ServerRegistrationsTest {

    private interface SamplePort {
        String id();
    }

    @AfterEach
    void clearPorts() {
        TianshuRuntimePorts.clear();
    }

    @Test
    void closingRemovesThePortsItInstalled() {
        ServerRegistrations registrations = new ServerRegistrations();
        SamplePort first = () -> "first";
        registrations.port(SamplePort.class, first);
        assertSame(first, TianshuRuntimePorts.find(SamplePort.class).orElse(null));

        registrations.close();

        assertTrue(TianshuRuntimePorts.find(SamplePort.class).isEmpty(),
                "a server-scoped port must not outlive the server that was closed");
    }

    @Test
    void closingDoesNotRemoveSomebodyElsesNewerRegistration() {
        ServerRegistrations registrations = new ServerRegistrations();
        registrations.port(SamplePort.class, () -> "old");
        SamplePort newer = () -> "newer";
        TianshuRuntimePorts.register(SamplePort.class, newer);

        registrations.close();

        assertSame(newer, TianshuRuntimePorts.find(SamplePort.class).orElse(null),
                "a stop must take back only what it installed");
    }

    @Test
    void serverToolsLeaveWithTheWorldThatRegisteredThem() {
        try (var registrations = new ServerRegistrations()) {
            BuiltinServerTools.forEach(registrations::tool);
            assertTrue(TianshuTools.find(BodyCommandRunTool.ID) instanceof BodyCommandRunTool);
        }
        BuiltinServerTools.forEach(tool -> assertNull(TianshuTools.find(tool.id())));
    }

    @Test
    void anOccupiedToolStaysWithItsOriginalProvider() {
        var existing = new BodyCommandRunTool();
        TianshuTools.registerBuiltin(existing);
        try {
            try (var registrations = new ServerRegistrations()) {
                BuiltinServerTools.forEach(registrations::tool);
                assertSame(existing, TianshuTools.find(existing.id()));
            }
            assertSame(existing, TianshuTools.find(existing.id()));
        } finally {
            TianshuTools.unregister(existing);
        }
    }

    @Test
    void aSecondSessionReplacesTheFirstWithoutLeakingIt() {
        ServerRegistrations first = new ServerRegistrations();
        first.port(SamplePort.class, () -> "first");
        first.close();

        ServerRegistrations second = new ServerRegistrations();
        SamplePort implementation = () -> "second";
        second.port(SamplePort.class, implementation);

        Optional<SamplePort> found = TianshuRuntimePorts.find(SamplePort.class);
        assertTrue(found.isPresent());
        assertSame(implementation, found.get());
        second.close();
        assertFalse(TianshuRuntimePorts.find(SamplePort.class).isPresent());
    }
}