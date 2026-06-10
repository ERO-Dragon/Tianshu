package com.rheinmetal.tianshu.function.asr.engine;

import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;

import java.nio.file.Path;
import java.util.Locale;

public enum AsrHotwordSupport {
    NONE,
    RELOAD_REQUIRED;

    public boolean reloadRequired() {
        return this == RELOAD_REQUIRED;
    }

    public static AsrHotwordSupport fromModel(AsrModelInfo modelInfo) {
        if (modelInfo == null) {
            return NONE;
        }
        String architecture = modelInfo.architecture().toLowerCase(Locale.ROOT);
        return "transducer".equals(architecture) ? RELOAD_REQUIRED : NONE;
    }

    public static AsrHotwordSupport fromModelPath(Path modelPath) {
        if (modelPath == null || modelPath.getFileName() == null) {
            return NONE;
        }
        AsrModelInfo modelInfo = AsrModelManager.getModelByLocalKey(modelPath.getFileName().toString());
        return fromModel(modelInfo);
    }
}
