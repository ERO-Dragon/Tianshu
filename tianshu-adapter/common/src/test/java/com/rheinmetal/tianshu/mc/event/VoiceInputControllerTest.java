package com.rheinmetal.tianshu.mc.event;

import com.rheinmetal.tianshu.api.assembly.InputSignals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The platform states what happened and stops there: it does not look up a service and does not ask whether
 * anything can consume the gesture. The signals are the only thing the platform owns here; presence is no
 * longer fed from this path.
 *
 * <p>The third argument is the session gate: while the world is starting, or a module reports loading, the key
 * must not open the microphone. A press that is already active when the gate closes is released explicitly, so the
 * speech module is never left capturing with no end event.</p>
 */
final class VoiceInputControllerTest {

    @AfterEach
    void clearBus() {
        InputSignals.clear();
    }

    @Test
    void pushToTalkPublishesDownOncePerPressAndUpOnRelease() {
        List<String> published = new ArrayList<>();
        subscribeAll(published);
        VoiceInputController controller = new VoiceInputController();

        controller.tick(true, false, true);
        controller.tick(true, true, true);
        controller.tick(true, true, true);
        controller.tick(true, false, true);

        assertEquals(List.of(InputSignals.PUSH_TO_TALK_DOWN, InputSignals.PUSH_TO_TALK_UP), published,
                "a held key is one press, not one per tick");
    }

    @Test
    void anUnavailableKeyDropsStaleStateInsteadOfPublishing() {
        List<String> published = new ArrayList<>();
        subscribeAll(published);
        VoiceInputController controller = new VoiceInputController();

        controller.tick(true, true, true);
        controller.tick(false, true, true);
        controller.tick(true, false, true);

        assertEquals(List.of(InputSignals.PUSH_TO_TALK_DOWN), published,
                "after the key became unavailable the release must not publish a second end");
    }

    @Test
    void aClosedSessionGateReleasesAnActivePressExactlyOnce() {
        List<String> published = new ArrayList<>();
        subscribeAll(published);
        VoiceInputController controller = new VoiceInputController();

        controller.tick(true, true, true);
        controller.tick(true, true, false);
        controller.tick(true, true, false);
        controller.tick(true, true, true);
        controller.tick(true, false, true);

        assertEquals(List.of(InputSignals.PUSH_TO_TALK_DOWN, InputSignals.PUSH_TO_TALK_UP), published,
                "the gate closes the microphone instead of forgetting the press, and the held key is not re-opened");
    }

    @Test
    void aFreshPressAfterTheGateReopensStartsACaptureAgain() {
        List<String> published = new ArrayList<>();
        subscribeAll(published);
        VoiceInputController controller = new VoiceInputController();

        controller.tick(true, true, true);
        controller.tick(true, true, false);
        controller.tick(true, false, false);
        controller.tick(true, true, true);
        controller.tick(true, false, true);

        assertEquals(List.of(
                        InputSignals.PUSH_TO_TALK_DOWN,
                        InputSignals.PUSH_TO_TALK_UP,
                        InputSignals.PUSH_TO_TALK_DOWN,
                        InputSignals.PUSH_TO_TALK_UP),
                published);
    }

    @Test
    void aClosedSessionGateSwallowsNewPresses() {
        List<String> published = new ArrayList<>();
        subscribeAll(published);
        VoiceInputController controller = new VoiceInputController();

        controller.tick(true, true, false);
        controller.tick(true, false, false);
        controller.tick(true, false, true);

        assertEquals(List.of(), published, "no press was ever accepted while the session was starting");
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