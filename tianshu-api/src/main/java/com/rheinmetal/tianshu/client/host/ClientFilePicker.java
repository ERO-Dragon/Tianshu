package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ClientFilePicker {
    /** Returns immediately. Cancelling the future dismisses the pending dialog. */
    CompletableFuture<Optional<Path>> chooseWavFile(UiText title);
}
