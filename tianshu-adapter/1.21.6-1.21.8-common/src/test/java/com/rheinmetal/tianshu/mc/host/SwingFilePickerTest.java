package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.client.api.text.UiText;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.nio.file.Path;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import static org.junit.jupiter.api.Assertions.*;

class SwingFilePickerTest {
    private final ClientTextProvider texts = new ClientTextProvider() {
        public String text(UiText text) { return text.value(); }
        public String currentLanguage() { return "en_us"; }
        public boolean hasKey(String key) { return false; }
    };

    @Test
    void fileSelectionPortMustReturnBeforeTheUserResponds() throws Exception {
        // Verify the async contract before any native dialog is opened.
        assertEquals(CompletableFuture.class,
                SwingFilePicker.class
                        .getMethod("chooseFile", UiText.class, String.class, UiText.class)
                        .getReturnType());
    }

    @Test
    void pendingDialogDoesNotBlockCallerAndDuplicateRequestSharesIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        try (var picker = new SwingFilePicker(texts, () -> new JFileChooser() {
            @Override public int showOpenDialog(Component parent) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                setSelectedFile(Path.of("voice.wav").toFile());
                return APPROVE_OPTION;
            }
        })) {
            var selected = picker.chooseFile(UiText.literal("voice"), "wav", UiText.literal("Audio"));
            assertFalse(selected.isDone());
            assertSame(selected, picker.chooseFile(UiText.literal("voice"), "wav", UiText.literal("Audio")));
            release.countDown();
            assertEquals(Path.of("voice.wav"), selected.get(10, TimeUnit.SECONDS).orElseThrow());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingBeforeDispatchCancelsWithoutOpeningWindow() throws Exception {
        AtomicInteger created = new AtomicInteger();
        var picker = new SwingFilePicker(texts, () -> { created.incrementAndGet(); return new JFileChooser(); });
        SwingUtilities.invokeAndWait(() -> {
            var selected = picker.chooseFile(UiText.literal("voice"), "wav", UiText.literal("Audio"));
            picker.close();
            assertTrue(selected.isCancelled());
            assertTrue(picker.chooseFile(UiText.literal("voice"), "wav", UiText.literal("Audio")).isCompletedExceptionally());
        });
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, created.get());
    }

    @Test
    void dialogFailureCompletesExceptionallyAndAllowsRetry() throws Exception {
        var attempts = new AtomicInteger();
        try (var picker = new SwingFilePicker(texts, () -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("dialog unavailable");
            return new JFileChooser() {
                @Override public int showOpenDialog(Component parent) { return CANCEL_OPTION; }
            };
        })) {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> picker.chooseFile(UiText.literal("voice"), "wav", UiText.literal("Audio")).get(10, TimeUnit.SECONDS));
            assertTrue(picker.chooseFile(UiText.literal("voice"), "wav", UiText.literal("Audio")).get(10, TimeUnit.SECONDS).isEmpty());
        }
    }
}
