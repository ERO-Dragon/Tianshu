package com.rheinmetal.tianshu.neoforge.adapter;

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

class NeoForgeClientFilePickerTest {
    private final ClientTextProvider texts = new ClientTextProvider() {
        public String text(UiText text) { return text.value(); }
        public String currentLanguage() { return "en_us"; }
    };

    @Test
    void fileSelectionPortMustReturnBeforeTheUserResponds() throws Exception {
        // Verify the async contract before any native dialog is opened.
        assertEquals(CompletableFuture.class,
                NeoForgeClientFilePicker.class.getMethod("chooseWavFile", UiText.class).getReturnType());
    }

    @Test
    void pendingDialogDoesNotBlockCallerAndDuplicateRequestSharesIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try (var picker = new NeoForgeClientFilePicker(texts, () -> new JFileChooser() {
            @Override public int showOpenDialog(Component parent) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                setSelectedFile(Path.of("voice.wav").toFile());
                return APPROVE_OPTION;
            }
        })) {
            var selected = picker.chooseWavFile(UiText.literal("voice"));
            assertFalse(selected.isDone());
            assertSame(selected, picker.chooseWavFile(UiText.literal("voice")));
            release.countDown();
            assertEquals(Path.of("voice.wav"), selected.get(5, TimeUnit.SECONDS).orElseThrow());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingBeforeDispatchCancelsWithoutOpeningWindow() throws Exception {
        AtomicInteger created = new AtomicInteger();
        var picker = new NeoForgeClientFilePicker(texts, () -> { created.incrementAndGet(); return new JFileChooser(); });
        SwingUtilities.invokeAndWait(() -> {
            var selected = picker.chooseWavFile(UiText.literal("voice"));
            picker.close();
            assertTrue(selected.isCancelled());
            assertTrue(picker.chooseWavFile(UiText.literal("voice")).isCompletedExceptionally());
        });
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, created.get());
    }

    @Test
    void dialogFailureCompletesExceptionallyAndAllowsRetry() throws Exception {
        var attempts = new AtomicInteger();
        try (var picker = new NeoForgeClientFilePicker(texts, () -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("dialog unavailable");
            return new JFileChooser() {
                @Override public int showOpenDialog(Component parent) { return CANCEL_OPTION; }
            };
        })) {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> picker.chooseWavFile(UiText.literal("voice")).get(5, TimeUnit.SECONDS));
            assertTrue(picker.chooseWavFile(UiText.literal("voice")).get(5, TimeUnit.SECONDS).isEmpty());
        }
    }
}
