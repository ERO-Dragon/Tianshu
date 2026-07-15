package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.client.host.ClientFilePicker;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.api.text.UiText;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Path;
import java.util.Optional;

public final class NeoForgeClientFilePicker implements ClientFilePicker {
    private final ClientTextProvider textProvider;

    public NeoForgeClientFilePicker(ClientTextProvider textProvider) {
        this.textProvider = textProvider;
    }

    @Override
    public Optional<Path> chooseWavFile(UiText title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(textProvider.text(title));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(textProvider.text(UiText.key("tianshu.gui.tts.dialog.wav_audio")), "wav"));
        int result = chooser.showOpenDialog(null);
        return result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null
                ? Optional.of(chooser.getSelectedFile().toPath())
                : Optional.empty();
    }
}
