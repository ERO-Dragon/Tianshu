package com.rheinmetal.tianshu.model;

import java.util.Collections;
import java.util.List;

public class ModelFilesMissingException extends Exception {

    private final String modelName;
    private final List<String> missingFiles;

    public ModelFilesMissingException(String modelName, List<String> missingFiles) {
        super("ASR 模型 [" + modelName + "] 缺失文件: " + String.join(", ", missingFiles));
        this.modelName = modelName;
        this.missingFiles = Collections.unmodifiableList(missingFiles);
    }

    public String getModelName() {
        return modelName;
    }

    public List<String> getMissingFiles() {
        return missingFiles;
    }
}
