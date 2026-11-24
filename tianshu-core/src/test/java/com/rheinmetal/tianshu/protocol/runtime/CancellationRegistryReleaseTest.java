package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancellation callback belongs to one envelope's lifetime, not to the process's.
 *
 * <p>The registry used to drop callbacks only when something was cancelled. A request that completed normally — the
 * common case, and the one that happens on every LLM turn — left its callback behind, and with it whatever the lambda
 * captured.</p>
 */
class CancellationRegistryReleaseTest {

    @Test
    void aCompletedEnvelopeReleasesItsCancellationCallback() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        CancellationRegistry registry = new CancellationRegistry(store);
        store.accept(envelope("llm-turn-1"));

        AtomicInteger fired = new AtomicInteger();
        registry.onCancel("llm-turn-1", envelope -> fired.incrementAndGet());
        assertEquals(1, registry.pendingRegistrations());

        store.transition("llm-turn-1", EnvelopeStatus.COMPLETED, "COMPLETED", "");

        assertEquals(0, registry.pendingRegistrations(),
                "a finished request must not keep its callback, or every normal turn leaks one");
        assertEquals(0, fired.get(), "completion is not cancellation: the callback must not run");
    }

    @Test
    void aCancelledEnvelopeStillFiresAndThenReleases() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        CancellationRegistry registry = new CancellationRegistry(store);
        store.accept(envelope("llm-turn-2"));

        AtomicInteger fired = new AtomicInteger();
        registry.onCancel("llm-turn-2", envelope -> fired.incrementAndGet());

        registry.cancelSelf("llm-turn-2", "CANCELLED", "user pressed stop");

        assertEquals(1, fired.get(), "the whole point of the callback is to hear about the cancel");
        assertEquals(0, registry.pendingRegistrations(), "and it is dropped once fired");
        assertEquals(EnvelopeStatus.CANCELLED, store.statusOf("llm-turn-2"));
    }

    @Test
    void aCallbackRegisteredAfterTheEnvelopeEndedIsNotKeptForever() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        CancellationRegistry registry = new CancellationRegistry(store);
        store.accept(envelope("llm-turn-3"));
        store.transition("llm-turn-3", EnvelopeStatus.COMPLETED, "COMPLETED", "");

        // The "cancel first, register after" ordering: the registration must not outlive a request that is already over.
        registry.onCancel("llm-turn-3", envelope -> { });
        registry.cancelSelf("llm-turn-3", "CANCELLED", "late cancel");

        assertEquals(EnvelopeStatus.COMPLETED, store.statusOf("llm-turn-3"),
                "a finished envelope cannot be cancelled after the fact");
        assertEquals(0, registry.pendingRegistrations());
    }

    @Test
    void registrationAfterCancelReceivesTheTerminalEnvelopeExactlyOnce() {
        var store = new EnvelopeLifecycleStore();
        var registry = new CancellationRegistry(store);
        store.accept(envelope("late"));
        registry.cancelSelf("late", "CANCELLED", "");
        var fired = new AtomicInteger();
        registry.onCancel("late", ignored -> fired.incrementAndGet());
        assertEquals(1, fired.get());
        assertEquals(0, registry.pendingRegistrations());
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope envelope(String id) {
        return EnvelopeBuilder.commandToCapability("test.caller", "test.target", PayloadType.NONE,
                        EmptyPayload.INSTANCE)
                .envelopeId(id)
                .threadPolicy(ThreadPolicy.ASYNC_WORKER)
                .build();
    }

    private enum EmptyPayload implements ITianshuPayload {
        INSTANCE
    }
}