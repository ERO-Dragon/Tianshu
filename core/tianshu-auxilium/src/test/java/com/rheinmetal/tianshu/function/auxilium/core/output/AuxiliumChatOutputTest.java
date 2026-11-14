package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientPresentationPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The final reply is one chat line; the stream is not a wall of lines. */
class AuxiliumChatOutputTest {

    private final RecordingPresentation presentation = new RecordingPresentation();
    private final AuxiliumChatOutput output = new AuxiliumChatOutput(presentation);

    @Test
    void onlyTheCompletedTextReachesChat() {
        output.begin(null);
        output.append(null, "partial ");
        output.append(null, "partial ");
        output.complete(null, "the full answer");

        assertEquals(1, presentation.texts.size());
        assertEquals("the full answer", presentation.texts.get(0).value());
        assertEquals(false, presentation.texts.get(0).translatable());
    }

    @Test
    void aBlankReplyDrawsNothing() {
        output.complete(null, "   ");
        output.complete(null, null);
        assertTrue(presentation.texts.isEmpty());
    }

    @Test
    void aFailureUsesTheLocalizedKeyRatherThanAnInternalReason() {
        output.fail(null, "connection reset by peer");

        assertEquals(1, presentation.texts.size());
        assertEquals(AuxiliumChatOutput.FAILURE_KEY, presentation.texts.get(0).value());
        assertTrue(presentation.texts.get(0).translatable());
    }

    private static final class RecordingPresentation implements ClientPresentationPort {
        private final List<UiText> texts = new ArrayList<>();

        @Override
        public void chat(UiText text) {
            texts.add(text);
        }

        @Override
        public void actionBar(UiText text) {
        }

        @Override
        public void toast(UiText title, UiText description) {
        }

        @Override
        public void title(UiText title, UiText subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        }

        @Override
        public void playSound(String soundId, float volume, float pitch) {
        }
    }
}
