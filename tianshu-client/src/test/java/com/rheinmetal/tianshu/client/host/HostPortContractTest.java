package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostPortContractTest {
    @Test
    void uiTextCopiesAndNormalizesArguments() {
        Object[] arguments = {"alpha", 2, null};
        UiText text = UiText.key("tianshu.test", arguments);

        arguments[0] = "changed";

        assertTrue(text.translatable());
        assertEquals("tianshu.test", text.value());
        assertEquals("alpha", text.arguments().get(0));
        assertEquals(2, text.arguments().get(1));
        assertEquals("", text.arguments().get(2));
    }

    @Test
    void literalTextNeverRetainsTranslationArguments() {
        UiText text = UiText.literal(null);

        assertFalse(text.translatable());
        assertEquals("", text.value());
        assertTrue(text.arguments().isEmpty());
    }

    @Test
    void schedulerAndUiHostRemainNonBlockingPorts() {
        AtomicBoolean executed = new AtomicBoolean();
        ClientScheduler scheduler = new ClientScheduler() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }

            @Override
            public boolean isOnMainThread() {
                return true;
            }
        };
        AtomicReference<UiText> status = new AtomicReference<>();
        ClientUiHost uiHost = new ClientUiHost() {
            @Override
            public void openSettings() {
            }

            @Override
            public void requestSettingsRefresh() {
            }

            @Override
            public void showStatus(UiText text, long durationMillis) {
                status.set(text);
            }
        };

        scheduler.execute(() -> executed.set(true));
        uiHost.showStatus(UiText.literal("ready"), 500L);

        assertTrue(executed.get());
        assertTrue(scheduler.isOnMainThread());
        assertEquals("ready", status.get().value());
    }

    @Test
    void filePickerReturnsOnlyPortablePathValues() {
        ClientFilePicker picker = title -> Optional.of(Path.of("voice.wav"));

        assertEquals(Path.of("voice.wav"), picker.chooseWavFile(UiText.key("choose.voice")).orElseThrow());
    }

    @Test
    void joinedTextPreservesTranslatablePartsWithoutResolvingThem() {
        UiText joined = UiText.join(", ", List.of(UiText.key("first"), UiText.literal("second")));

        assertTrue(joined.composite());
        assertFalse(joined.isBlank());
        assertEquals(
                List.of(UiText.key("first"), UiText.literal(", "), UiText.literal("second")),
                joined.parts()
        );
    }
}
