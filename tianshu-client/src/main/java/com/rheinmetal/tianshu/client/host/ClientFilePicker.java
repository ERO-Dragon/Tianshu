package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.ui.UiText;

import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
public interface ClientFilePicker {
    Optional<Path> chooseWavFile(UiText title);
}
