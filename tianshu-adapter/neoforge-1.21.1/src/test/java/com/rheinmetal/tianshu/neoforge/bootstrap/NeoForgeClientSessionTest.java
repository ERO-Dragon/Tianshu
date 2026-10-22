package com.rheinmetal.tianshu.neoforge.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NeoForgeClientSessionTest {
    @Test
    void closesOwnedResourcesInReverseOrderOnlyOnce() {
        List<String> closed = new ArrayList<>();
        NeoForgeClientSession session = new NeoForgeClientSession();
        session.own(() -> closed.add("runtime"));
        session.own(() -> closed.add("presence"));
        session.own(() -> closed.add("events"));

        session.close();
        session.close();

        assertEquals(List.of("events", "presence", "runtime"), closed);
    }

    @Test
    void continuesClosingAfterFailureAndReportsTheFirstFailure() {
        List<String> closed = new ArrayList<>();
        NeoForgeClientSession session = new NeoForgeClientSession();
        session.own(() -> closed.add("runtime"));
        session.own(() -> {
            closed.add("integration");
            throw new IllegalStateException("integration close failed");
        });
        session.own(() -> closed.add("events"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, session::close);

        assertEquals("integration close failed", failure.getMessage());
        assertEquals(List.of("events", "integration", "runtime"), closed);
    }

    @Test
    void canTransferPreliminaryResourceOwnershipToTheFinalRuntime() {
        List<String> closed = new ArrayList<>();
        NeoForgeClientSession session = new NeoForgeClientSession();
        NeoForgeClientSession.Ownership preliminary = session.own(() -> closed.add("preliminary"));
        session.own(() -> closed.add("runtime"));

        preliminary.release();
        session.close();

        assertEquals(List.of("runtime"), closed);
    }
}
