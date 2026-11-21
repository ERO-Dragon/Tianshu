package com.rheinmetal.tianshu.dubhe.dialogue.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunTermination;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateDelivery;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateOutcomeKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter between the shared producers and the voice boundary: it must translate, never embellish.
 */
class DubheSpeechBridgeTest {

    @Test
    void conclusionIsSpokenAsLiteralTextExactlyOnce() {
        RecordingOutlet outlet = new RecordingOutlet();
        DubheSpeechBridge bridge = new DubheSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(new DubheDelegateDelivery(
                "dubhe.delegate.1",
                DubheDelegateOutcomeKind.OK,
                DubheRunTermination.COMPLETED,
                "Iron blocks can be mined with a stone pickaxe.",
                List.of("wiki"),
                null));

        assertEquals(1, outlet.lines.size(), "one delivery is one line");
        DubheSpeechLine line = outlet.lines.get(0);
        assertEquals(DubheSpeechSource.DELEGATE, line.source());
        assertEquals(DubheSpeechPriority.HIGH, line.priority());
        assertEquals("Iron blocks can be mined with a stone pickaxe.", line.text(), "the conclusion is spoken as it is");
        assertTrue(!line.isKeyed());
    }

    @Test
    void receiptIsSpokenAsLowPriorityKey() {
        RecordingOutlet outlet = new RecordingOutlet();
        DubheSpeechBridge bridge = new DubheSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(DubheDelegateDelivery.ack("dubhe.delegate.2"));

        DubheSpeechLine line = outlet.lines.get(0);
        assertEquals(DubheSpeechSource.DELEGATE, line.source());
        assertEquals(DubheSpeechPriority.NORMAL, line.priority(), "a receipt joins the queue behind the current line");
        assertTrue(line.isKeyed());
        assertEquals(DubheDelegateOutcomeKind.ACK_SPEECH_KEY, line.key());
    }

    @Test
    void failureIsSpokenThroughItsFallbackKey() {
        RecordingOutlet outlet = new RecordingOutlet();
        DubheSpeechBridge bridge = new DubheSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(new DubheDelegateDelivery(
                "dubhe.delegate.3",
                DubheDelegateOutcomeKind.TIMEOUT,
                DubheRunTermination.ABORT_TIMEOUT,
                "",
                List.of(),
                "dubhe.runtime.delegate.timeout"));

        DubheSpeechLine line = outlet.lines.get(0);
        assertEquals("dubhe.runtime.delegate.timeout", line.key());
        assertEquals(DubheSpeechPriority.HIGH, line.priority(), "a terminal notice is what the player waits for");
    }

    @Test
    void eventSentenceIsSpokenAsLiteralEventLine() {
        RecordingOutlet outlet = new RecordingOutlet();
        DubheSpeechBridge bridge = new DubheSpeechBridge(outlet, LogSink.NOOP);

        bridge.speak("tianshu:death", "You died: fell from a high place");

        DubheSpeechLine line = outlet.lines.get(0);
        assertEquals(DubheSpeechSource.EVENT, line.source());
        assertEquals("You died: fell from a high place", line.text());
    }

    @Test
    void emptyContentIsDroppedInsteadOfSpoken() {
        RecordingOutlet outlet = new RecordingOutlet();
        DubheSpeechBridge bridge = new DubheSpeechBridge(outlet, LogSink.NOOP);

        bridge.publish(new DubheDelegateDelivery(
                "dubhe.delegate.4", DubheDelegateOutcomeKind.FAILED, DubheRunTermination.FAILED, "", List.of(), null));
        bridge.speak("tianshu:death", "   ");
        bridge.speak("tianshu:death", null);

        assertTrue(outlet.lines.isEmpty(), "a line with neither key nor text must stay silent");
    }

    @Test
    void aThrowingOutletNeverEscapesTheProducer() {
        DubheSpeechBridge bridge = new DubheSpeechBridge(line -> {
            throw new IllegalStateException("voice device is gone");
        }, LogSink.NOOP);

        bridge.publish(new DubheDelegateDelivery(
                "dubhe.delegate.5", DubheDelegateOutcomeKind.OK, DubheRunTermination.COMPLETED, "some conclusion",
                List.of(), null));
        bridge.speak("tianshu:death", "You died");
    }

    private static final class RecordingOutlet implements DubheSpeechOutlet {
        private final List<DubheSpeechLine> lines = new ArrayList<>();

        @Override
        public void speak(DubheSpeechLine line) {
            lines.add(line);
        }
    }
}
