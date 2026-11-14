package com.rheinmetal.tianshu.function.auxilium.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDelivery;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateOutcomeKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter between the shared producers and the voice boundary: it must translate, never embellish.
 */
class AXSpeechBridgeTest {

    @Test
    void conclusionIsSpokenAsLiteralTextExactlyOnce() {
        RecordingOutlet outlet = new RecordingOutlet();
        AXSpeechBridge bridge = new AXSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(new AXDelegateDelivery(
                "ax.delegate.1",
                AXDelegateOutcomeKind.OK,
                AXRunTermination.COMPLETED,
                "Iron blocks can be mined with a stone pickaxe.",
                List.of("wiki"),
                null));

        assertEquals(1, outlet.lines.size(), "one delivery is one line");
        AXSpeechLine line = outlet.lines.get(0);
        assertEquals(AXSpeechSource.DELEGATE, line.source());
        assertEquals(AXSpeechPriority.HIGH, line.priority());
        assertEquals("Iron blocks can be mined with a stone pickaxe.", line.text(), "the conclusion is spoken as it is");
        assertTrue(!line.isKeyed());
    }

    @Test
    void receiptIsSpokenAsLowPriorityKey() {
        RecordingOutlet outlet = new RecordingOutlet();
        AXSpeechBridge bridge = new AXSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(AXDelegateDelivery.ack("ax.delegate.2"));

        AXSpeechLine line = outlet.lines.get(0);
        assertEquals(AXSpeechSource.DELEGATE, line.source());
        assertEquals(AXSpeechPriority.NORMAL, line.priority(), "a receipt joins the queue behind the current line");
        assertTrue(line.isKeyed());
        assertEquals(AXDelegateOutcomeKind.ACK_SPEECH_KEY, line.key());
    }

    @Test
    void failureIsSpokenThroughItsFallbackKey() {
        RecordingOutlet outlet = new RecordingOutlet();
        AXSpeechBridge bridge = new AXSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(new AXDelegateDelivery(
                "ax.delegate.3",
                AXDelegateOutcomeKind.TIMEOUT,
                AXRunTermination.ABORT_TIMEOUT,
                "",
                List.of(),
                "ax.runtime.delegate.timeout"));

        AXSpeechLine line = outlet.lines.get(0);
        assertEquals("ax.runtime.delegate.timeout", line.key());
        assertEquals(AXSpeechPriority.HIGH, line.priority(), "a terminal notice is what the player waits for");
    }

    @Test
    void eventSentenceIsSpokenAsLiteralEventLine() {
        RecordingOutlet outlet = new RecordingOutlet();
        AXSpeechBridge bridge = new AXSpeechBridge(outlet, LogSink.NOOP);

        bridge.speak("tianshu:death", "You died: fell from a high place");

        AXSpeechLine line = outlet.lines.get(0);
        assertEquals(AXSpeechSource.EVENT, line.source());
        assertEquals("You died: fell from a high place", line.text());
    }

    @Test
    void emptyContentIsDroppedInsteadOfSpoken() {
        RecordingOutlet outlet = new RecordingOutlet();
        AXSpeechBridge bridge = new AXSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(new AXDelegateDelivery(
                "ax.delegate.4", AXDelegateOutcomeKind.FAILED, AXRunTermination.FAILED, "", List.of(), null));
        bridge.speak("tianshu:death", "   ");
        bridge.speak("tianshu:death", null);

        assertTrue(outlet.lines.isEmpty(), "a line with neither key nor text must stay silent");
    }

    @Test
    void aThrowingOutletNeverEscapesTheProducer() {
        AXSpeechBridge bridge = new AXSpeechBridge(line -> {
            throw new IllegalStateException("voice device is gone");
        }, LogSink.NOOP);

        bridge.publish(new AXDelegateDelivery(
                "ax.delegate.5", AXDelegateOutcomeKind.OK, AXRunTermination.COMPLETED, "some conclusion",
                List.of(), null));
        bridge.speak("tianshu:death", "You died");
    }

    private static final class RecordingOutlet implements AXSpeechOutlet {
        private final List<AXSpeechLine> lines = new ArrayList<>();

        @Override
        public void speak(AXSpeechLine line) {
            lines.add(line);
        }
    }
}
