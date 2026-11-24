package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.lifecycle.status.ModuleLifecycleStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a module entered is what gets undone.
 *
 * <p>Cleanup used to walk the list of phases that succeeded. A module that registered a handler and then threw during
 * {@code register} appeared only in the failed status and never in the "registered" list, so its handler stayed routable
 * for the life of the session while diagnostics called the module FAILED.</p>
 */
class ModuleHostEnteredPhaseCleanupTest {

    @Test
    void aModuleThatThrewInPrepareIsStillDestroyed() {
        Host host = new Host();
        List<String> events = new ArrayList<>();
        host.register(new ThrowingModule("boom.in.prepare", Phase.PREPARE, events));
        host.register(new FineModule("fine", events));

        host.registerAll(null);
        host.prepareAll(null);
        host.destroyAll();

        assertTrue(events.contains("prepare:boom.in.prepare"),
                "the phase was entered, so it must have been recorded: " + events);
        assertTrue(events.contains("destroy:boom.in.prepare"),
                "a module whose prepare threw may still hold a thread or a handle: " + events);
        assertTrue(events.contains("destroy:fine"));
    }

    @Test
    void aModuleThatThrewInRegisterIsStillUnregistered() {
        Host host = new Host();
        List<String> events = new ArrayList<>();
        host.register(new ThrowingModule("boom.in.register", Phase.REGISTER, events));

        host.registerAll(null);
        List<String> unregistered = new ArrayList<>();
        host.unregisterAll(unregistered::add);

        assertEquals(List.of("boom.in.register"), unregistered,
                "the handler the module registered before throwing has to be removed again");
    }

    @Test
    void aModuleThatThrewInStartIsStillStopped() {
        Host host = new Host();
        List<String> events = new ArrayList<>();
        host.register(new ThrowingModule("boom.in.start", Phase.START, events));

        host.registerAll(null);
        host.prepareAll(null);
        host.startAll(null);
        host.stopAll();

        assertTrue(events.contains("stop:boom.in.start"),
                "a start that threw may have opened half of what stop closes: " + events);
    }

    @Test
    void anInstallationThatNeverEnteredAPhaseIsNotCleanedForIt() {
        Host host = new Host();
        List<String> events = new ArrayList<>();
        host.register(new FineModule("only.registered", events));

        host.registerAll(null);
        host.destroyAll();
        host.stopAll();

        assertTrue(events.stream().noneMatch(event -> event.startsWith("destroy:")),
                "destroy belongs to modules that were prepared: " + events);
        assertTrue(events.stream().noneMatch(event -> event.startsWith("stop:")),
                "stop belongs to modules that were started: " + events);
    }

    private static final class Host extends TianshuModuleHost {
        private Host() {
            super(new QuietEnvironment());
        }

        private void register(TianshuManagedModule module) {
            registerOptionalModule(module);
        }
    }

    private enum Phase {
        REGISTER,
        PREPARE,
        START
    }

    private static class FineModule implements TianshuManagedModule {
        private final String moduleId;
        private final List<String> events;

        private FineModule(String moduleId, List<String> events) {
            this.moduleId = moduleId;
            this.events = events;
        }

        @Override
        public String moduleId() {
            return moduleId;
        }

        @Override
        public void register(com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext context) {
            events.add("register:" + moduleId);
        }

        @Override
        public void prepare(ModuleRuntimeContext context) {
            events.add("prepare:" + moduleId);
        }

        @Override
        public void start(ModuleRuntimeContext context) {
            events.add("start:" + moduleId);
        }

        @Override
        public void stop() {
            events.add("stop:" + moduleId);
        }

        @Override
        public void destroy() {
            events.add("destroy:" + moduleId);
        }
    }

    private static final class ThrowingModule extends FineModule {
        private final Phase throwIn;

        private ThrowingModule(String moduleId, Phase throwIn, List<String> events) {
            super(moduleId, events);
            this.throwIn = throwIn;
        }

        @Override
        public void register(com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext context) {
            super.register(context);
            if (throwIn == Phase.REGISTER) {
                throw new IllegalStateException("registration failed after wiring a handler");
            }
        }

        @Override
        public void prepare(ModuleRuntimeContext context) {
            super.prepare(context);
            if (throwIn == Phase.PREPARE) {
                throw new IllegalStateException("prepare failed after opening a resource");
            }
        }

        @Override
        public void start(ModuleRuntimeContext context) {
            super.start(context);
            if (throwIn == Phase.START) {
                throw new IllegalStateException("start failed halfway");
            }
        }
    }

    /** Minimal environment: these tests only need the host's lifecycle bookkeeping. */
    private static final class QuietEnvironment implements IGameEnvironment {
        @Override
        public void executeOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public Path getGameDirectory() {
            return Path.of(".");
        }

        @Override
        public boolean isClientSide() {
            return true;
        }

        @Override
        public void openFolder(Path dir) {
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }

        @Override
        public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() {
            return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP;
        }
    }
}