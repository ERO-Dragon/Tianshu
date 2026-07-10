package com.rheinmetal.tianshu.function.llm.service;

import java.util.List;

interface RagTextAnalyzer {

    List<String> analyze(String text);
}
