package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.function.intake.input.AsrInputService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoForgeVoiceInputControllerTest {
    @Test
    void pushToTalkBeginsOnPressAndEndsOnRelease() {
        Fixture fixture = new Fixture();

        fixture.tick(false);
        fixture.tick(true);
        fixture.tick(true);
        fixture.tick(false);

        assertEquals(List.of("begin", "end"), fixture.operations);
        assertEquals(2, fixture.presenceInputs);
    }

    @Test
    void unavailableAsrCancelsActiveInputAndDropsStaleKeyState() {
        Fixture fixture = new Fixture();

        fixture.tick(true);
        fixture.service.accepting = false;
        fixture.tick(true);
        fixture.service.accepting = true;
        fixture.tick(false);

        assertEquals(List.of("begin", "cancel"), fixture.operations);
        assertEquals(2, fixture.presenceInputs);
    }

    private static final class Fixture {
        private final List<String> operations = new ArrayList<>();
        private final RecordingAsrInputService service = new RecordingAsrInputService(operations);
        private final NeoForgeVoiceInputController controller;
        private int presenceInputs;

        private Fixture() {
            controller = new NeoForgeVoiceInputController(
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
