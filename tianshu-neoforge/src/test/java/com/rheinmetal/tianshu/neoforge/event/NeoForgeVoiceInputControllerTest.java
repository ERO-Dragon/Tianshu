package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoForgeVoiceInputControllerTest {
    @Test
    void alwaysModeBeginsOnceAndCommitsOnEachKeyPressEdge() {
        Fixture fixture = new Fixture(TriggerMode.ALWAYS);

        fixture.tick(false);
        fixture.tick(true);
        fixture.tick(true);
        fixture.tick(false);
        fixture.tick(true);

        assertEquals(List.of("begin", "commit", "commit"), fixture.operations);
        assertEquals(3, fixture.presenceInputs);
    }

    @Test
    void pushToTalkBeginsOnPressAndEndsOnRelease() {
        Fixture fixture = new Fixture(TriggerMode.PUSH_TO_TALK);

        fixture.tick(false);
        fixture.tick(true);
        fixture.tick(true);
        fixture.tick(false);

        assertEquals(List.of("begin", "end"), fixture.operations);
        assertEquals(2, fixture.presenceInputs);
    }

    @Test
    void changingTriggerModeCancelsThePreviousInputState() {
        Fixture fixture = new Fixture(TriggerMode.PUSH_TO_TALK);

        fixture.tick(true);
        fixture.mode.set(TriggerMode.ALWAYS);
        fixture.tick(false);

        assertEquals(List.of("begin", "cancel", "begin"), fixture.operations);
        assertEquals(3, fixture.presenceInputs);
    }

    @Test
    void unavailableAsrCancelsActiveInputAndDropsStaleKeyState() {
        Fixture fixture = new Fixture(TriggerMode.PUSH_TO_TALK);

        fixture.tick(true);
        fixture.service.accepting = false;
        fixture.tick(true);
        fixture.service.accepting = true;
        fixture.tick(false);

        assertEquals(List.of("begin", "cancel"), fixture.operations);
        assertEquals(2, fixture.presenceInputs);
    }

    private static final class Fixture {
        private final AtomicReference<TriggerMode> mode;
        private final List<String> operations = new ArrayList<>();
        private final RecordingAsrInputService service = new RecordingAsrInputService(operations);
        private final NeoForgeVoiceInputController controller;
        private int presenceInputs;

        private Fixture(TriggerMode mode) {
            this.mode = new AtomicReference<>(mode);
            controller = new NeoForgeVoiceInputController(
                    this.mode::get,
                    () -> Optional.of(service),
                    () -> presenceInputs++
            );
        }

        private void tick(boolean keyDown) {
            controller.tick(true, keyDown);
        }
    }

    private static final class RecordingAsrInputService implements AsrInputService {
        private final List<String> operations;
        private boolean accepting = true;

        private RecordingAsrInputService(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public boolean canAcceptVoiceInput() {
            return accepting;
        }

        @Override
        public void beginVoiceInput() {
            operations.add("begin");
        }

        @Override
        public void endVoiceInput() {
            operations.add("end");
        }

        @Override
        public void commitVoiceInput() {
            operations.add("commit");
        }

        @Override
        public void cancelVoiceInput() {
            operations.add("cancel");
        }
    }
}
