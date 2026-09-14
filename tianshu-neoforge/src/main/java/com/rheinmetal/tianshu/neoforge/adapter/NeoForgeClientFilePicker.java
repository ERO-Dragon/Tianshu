package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.client.host.ClientFilePicker;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.api.text.UiText;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class NeoForgeClientFilePicker implements ClientFilePicker, AutoCloseable {
    private final ClientTextProvider textProvider;
    private final Supplier<JFileChooser> chooserFactory;
    private CompletableFuture<Optional<Path>> pending;
    private boolean closed;

    public NeoForgeClientFilePicker(ClientTextProvider textProvider) {
        this(textProvider, JFileChooser::new);
    }

    NeoForgeClientFilePicker(ClientTextProvider textProvider, Supplier<JFileChooser> chooserFactory) {
        this.textProvider = textProvider;
        this.chooserFactory = chooserFactory;
    }

    @Override
    public synchronized CompletableFuture<Optional<Path>> chooseWavFile(UiText title) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("FILE_PICKER_CLOSED"));
        if (pending != null && !pending.isDone()) {
            return pending;
        }
        // Resolve game-owned language state on the calling client thread.
        String dialogTitle = textProvider.text(title);
        String filterLabel = textProvider.text(UiText.key("tianshu.gui.tts.dialog.wav_audio"));
        var result = new CompletableFuture<Optional<Path>>();
        pending = result;
        SwingUtilities.invokeLater(() -> {
            if (result.isDone()) return;
            try {
                JFileChooser chooser = chooserFactory.get();
                chooser.setDialogTitle(dialogTitle);
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setFileFilter(new FileNameExtensionFilter(filterLabel, "wav"));
                result.whenComplete((ignored, failure) -> {
                    if (result.isCancelled()) SwingUtilities.invokeLater(chooser::cancelSelection);
                });
                if (result.isDone()) return;
                int selected = chooser.showOpenDialog(null);
                result.complete(selected == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null
                        ? Optional.of(chooser.getSelectedFile().toPath()) : Optional.empty());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) pending.cancel(false);
        pending = null;
    }
}
