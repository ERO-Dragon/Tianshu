package com.rheinmetal.tianshu.client.settings.module.tts;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientFilePicker;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Page-owned voice import; all state and UI callbacks are confined to the client thread. */
final class TtsVoiceImportController implements AutoCloseable {
    private final ClientFilePicker picker;
    private final ClientScheduler scheduler;
    private final BiConsumer<Path, Consumer<String>> importer;
    private final Consumer<String> completion;
    private final Runnable refresh;
    private CompletableFuture<Optional<Path>> selection;
    private boolean busy;
    private boolean closed;

    TtsVoiceImportController(ClientFilePicker picker, ClientScheduler scheduler,
                             BiConsumer<Path, Consumer<String>> importer, Consumer<String> completion, Runnable refresh) {
        this.picker = picker;
        this.scheduler = scheduler;
        this.importer = importer;
        this.completion = completion;
        this.refresh = refresh;
    }

    boolean busy() { return busy; }

    void start(UiText title) {
        if (closed || busy) return;
        busy = true;
        try {
            selection = picker.chooseWavFile(title);
            selection.whenComplete((path, failure) -> scheduler.execute(() -> {
                if (closed) return;
                selection = null;
                if (failure != null) {
                    finish(null);
                } else if (path.isEmpty()) {
                    busy = false;
                    refresh.run();
                } else {
                    try {
                        importer.accept(path.get(), name -> scheduler.execute(() -> finish(name)));
                    } catch (RuntimeException importFailure) {
                        finish(null);
                    }
                }
            }));
        } catch (RuntimeException failure) {
            finish(null);
        }
    }

    private void finish(String name) {
        if (closed || !busy) return;
        busy = false;
        completion.accept(name);
        refresh.run();
    }

    @Override
    public void close() {
        closed = true;
        busy = false;
        if (selection != null) {
            selection.cancel(false);
            selection = null;
        }
    }
}
