package com.rheinmetal.tianshu.client.settings.module.tts;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class TtsVoiceImportControllerTest {
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final ClientScheduler scheduler = new ClientScheduler() {
        public void execute(Runnable task) { queue.add(task); }
        public boolean isOnMainThread() { return true; }
    };
    private final List<String> completed = new ArrayList<>();
    private final AtomicInteger selections = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final AtomicInteger imports = new AtomicInteger();
    private final AtomicReference<Consumer<String>> imported = new AtomicReference<>();
    private CompletableFuture<Optional<Path>> selection;

    private TtsVoiceImportController controller() {
        return new TtsVoiceImportController(title -> {
            selections.incrementAndGet();
            return selection = new CompletableFuture<>();
        }, scheduler, (path, callback) -> {
            assertEquals(Path.of("voice.wav"), path);
            imports.incrementAndGet();
            imported.set(callback);
        }, completed::add, refreshes::incrementAndGet);
    }

    private void drain() { while (!queue.isEmpty()) queue.remove().run(); }

    @Test
    void pendingSelectionAndCopyPreventDuplicatesAndCompleteOnClientScheduler() {
        var controller = controller();
        controller.start(UiText.literal("voice"));
        controller.start(UiText.literal("voice"));
        assertEquals(1, selections.get());
        assertTrue(controller.busy());
        selection.complete(Optional.of(Path.of("voice.wav")));
        assertEquals(0, imports.get());
        drain();
        assertEquals(1, imports.get());
        assertTrue(controller.busy());
        imported.get().accept("copied.wav");
        assertTrue(completed.isEmpty());
        drain();
        assertEquals(List.of("copied.wav"), completed);
        assertFalse(controller.busy());
    }

    @Test
    void cancelAndDialogFailureRestoreTheActionForRetry() {
        var controller = controller();
        controller.start(UiText.literal("voice"));
        selection.complete(Optional.empty());
        drain();
        assertFalse(controller.busy());
        assertEquals(1, refreshes.get());
        assertEquals(0, imports.get());
        assertTrue(completed.isEmpty());
        controller.start(UiText.literal("voice"));
        selection.completeExceptionally(new IllegalStateException("dialog failed"));
        drain();
        assertFalse(controller.busy());
        assertEquals(1, completed.size());
        assertNull(completed.get(0));
        controller.start(UiText.literal("voice"));
        assertTrue(controller.busy());
    }

    @Test
    void closedPageCancelsSelectionAndRejectsQueuedAndLateCompletions() {
        var controller = controller();
        controller.start(UiText.literal("voice"));
        controller.close();
        assertTrue(selection.isCancelled());
        drain();
        assertEquals(0, imports.get());
        assertTrue(completed.isEmpty());

        controller = controller();
        controller.start(UiText.literal("voice"));
        selection.complete(Optional.of(Path.of("voice.wav")));
        controller.close();
        drain();
        assertEquals(0, imports.get());

        controller = controller();
        controller.start(UiText.literal("voice"));
        selection.complete(Optional.of(Path.of("voice.wav")));
        drain();
        controller.close();
        imported.get().accept("late.wav");
        drain();
        assertTrue(completed.isEmpty());
    }
}
