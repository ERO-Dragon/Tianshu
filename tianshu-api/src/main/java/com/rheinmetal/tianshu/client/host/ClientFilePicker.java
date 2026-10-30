package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The platform's file-open dialog.
 *
 * <p>It takes the shape of the question, not of one feature's answer: a title, one extension and the label of
 * the filter. The host therefore never carries a feature's vocabulary — a module that imports a `.wav` passes
 * {@code "wav"} and its own label, and a second module needing another type calls the same method.</p>
 */
@FunctionalInterface
public interface ClientFilePicker {
    /**
     * Opens a file dialog and returns immediately.
     *
     * @param title       the dialog title
     * @param extension   the extension to filter on, without the dot (for example {@code "wav"})
     * @param filterLabel the label shown in the dialog's filter dropdown
     */
    CompletableFuture<Optional<Path>> chooseFile(UiText title, String extension, UiText filterLabel);
}
