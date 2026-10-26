package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.assembly.InputSignals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The platform states what happened and stops there: it does not look up a service and does not ask whether
 * anything can consume the gesture. Every published signal is also a presence input, which is the only thing
 * the platform still owns here.
 */
final class NeoForgeVoiceInputControllerTest {

    @AfterEach
    void clearBus() {
        InputSignals.clear();
    }

    @Test
    void pushToTalkPublishesDownOncePerPressAndUpOnRelease() {
        List<String> published = new ArrayList<>();
        subscribeAll(published);
        int[] presenceInputs = {0};
        NeoForgeVoiceInputController controller = new NeoForgeVoiceInputController(() -> presenceInputs[0]++);

        controller.tick(true, false);
        controller.tick(true, true);
        controller.tick(true, true);
        controller.tick(true, false);

        assertEquals(List.of(InputSignals.PUSH_TO_TALK_DOWN, InputSignals.PUSH_TO_TALK_UP), published,
                "a held key is one press, not one per tick");
        assertEquals(2, presenceInputs[0], "press and release each count as presence input");
    }

    @Test
    void anUnavailableKeyDropsStaleStateInsteadOfPublishing() {
        List<String> published = new ArrayList<>();
        subscribeAll(published);
        NeoForgeVoiceInputController controller = new NeoForgeVoiceInputController(() -> {
        });

        controller.tick(true, true);
        controller.tick(false, true);
        controller.tick(true, false);

        assertEquals(List.of(InputSignals.PUSH_TO_TALK_DOWN), published,
                "after the key became unavailable the release must not publish a second end");
    }

    private static void subscribeAll(List<String> sink) {
        for (String channel : List.of(
                InputSignals.PUSH_TO_TALK_DOWN,
                InputSignals.PUSH_TO_TALK_UP,
                InputSignals.PUSH_TO_TALK_CANCEL)) {
            InputSignals.subscribe(channel, text -> sink.add(channel));
        }
    }
}
