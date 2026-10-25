package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.GameEventPort;
import com.rheinmetal.tianshu.api.game.GameEventFacts;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Auxilium's reaction to the player's situation.
 *
 * <p>The platform reports pure readings; this class owns the probe (the threshold state machine), the
 * wording it renders and the submission into the module's own event service. Nothing here is
 * privileged — it submits like any other producer.</p>
 */
public final class AuxiliumGameEvents implements GameEventPort {

    private final Supplier<AXGameEventService> serviceLookup;
    private final LogSink logs;
    private final AXGameEventProbe probe = new AXGameEventProbe();
    private final AXGameEventSentences sentences = new AXGameEventSentences();

    public AuxiliumGameEvents(Supplier<AXGameEventService> serviceLookup, LogSink logs) {
        this.serviceLookup = serviceLookup == null ? () -> null : serviceLookup;
        this.logs = logs == null ? LogSink.NOOP : logs;
    }

    @Override
    public void resetSession() {
        probe.reset();
    }

    @Override
    public void flush() {
        AXGameEventService service = service();
        if (service != null) {
            service.flush();
        }
    }

    @Override
    public void tick(long nowMillis) {
        AXGameEventService service = service();
        if (service != null && !service.closed()) {
            // The deferred window runs on the wall clock, so it advances on every tick rather than only
            // when a sample is due: otherwise the voluntary turn would wait for the next event.
            service.tick(nowMillis);
        }
    }

    @Override
    public void sample(GameEventFacts facts) {
        if (facts == null) {
            return;
        }
        AXGameEventService service = service();
        if (service == null || service.closed()) {
            return;
        }
        AXGameEventSample sample = new AXGameEventSample(
                Math.max(0, facts.health()),
                facts.foodLevel(),
                facts.dimensionId(),
                facts.biomeId(),
                facts.dead());
        List<AXGameEventSignal> signals = probe.sample(sample);
        if (signals.isEmpty()) {
            return;
        }
        for (AXGameEventSignal signal : signals) {
            Optional<AXEventDescriptor> descriptor = sentences.describe(signal, sample, facts.deathMessage());
            if (descriptor.isEmpty()) {
                logs.warn("ax.event.unrenderable_signal kind=" + signal.kind());
                continue;
            }
            service.submit(descriptor.get());
        }
    }

    private AXGameEventService service() {
        try {
            return serviceLookup.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
