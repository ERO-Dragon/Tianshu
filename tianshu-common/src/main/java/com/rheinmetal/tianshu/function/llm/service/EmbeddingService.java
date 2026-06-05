package com.rheinmetal.tianshu.function.llm.service;

import java.util.List;

public interface EmbeddingService {

    float[] embed(String text) throws Exception;

    float[][] embed(List<String> texts) throws Exception;

    int getEmbeddingDimension();
}
