package com.rheinmetal.tianshu.function.tts.synthesis;

import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.utils.PathUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class SherpaOnnxTtsConfigFactory {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final InferenceResourcePolicy resourcePolicy;

    public SherpaOnnxTtsConfigFactory(IGameEnvironment env, ITianshuConfig config) {
        this(env, config, InferenceResourcePolicy.systemDefault());
    }

    public SherpaOnnxTtsConfigFactory(IGameEnvironment env, ITianshuConfig config, InferenceResourcePolicy resourcePolicy) {
        this.env = env;
        this.config = config;
        this.resourcePolicy = resourcePolicy == null ? InferenceResourcePolicy.systemDefault() : resourcePolicy;
    }

    public Optional<ResolvedConfig> build(TtsResolvedModel model) {
        if (model == null || model.backendType() != TtsBackendType.SHERPA) {
            return Optional.empty();
        }
        File dir = model.modelDir().toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            env.error("TTS model directory does not exist: " + model.modelDir(), null);
            return Optional.empty();
        }
        File safeDir = PathUtils.getSafeModelDir(dir);
        if (safeDir == null) {
            env.error("Failed to resolve safe TTS model directory", null);
            return Optional.empty();
        }
        OfflineTtsConfig ttsConfig = model.modelInfo() == null
                ? buildLegacyConfig(safeDir, resolveLegacyModelName())
                : buildMetadataConfig(safeDir, model.modelInfo(), model.engineType(), null);
        if (ttsConfig == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedConfig(ttsConfig, ttsConfig.getModel().getZipvoice() != null));
    }

    private OfflineTtsConfig buildMetadataConfig(File modelDir, TtsModelInfo info, String engineType, String vocoderPath) {
        return switch (engineType) {
            case "kokoro" -> buildKokoroConfig(modelDir, info);
            case "matcha" -> buildMatchaConfig(modelDir, info, vocoderPath);
            case "zipvoice" -> buildZipVoiceConfig(modelDir, info);
            default -> buildVitsMetadataConfig(modelDir, info);
        };
    }

    private OfflineTtsConfig buildKokoroConfig(File modelDir, TtsModelInfo info) {
        String modelPath = resolveModelFile(modelDir, info.modelFiles);
        String tokensPath = findRequiredFile(modelDir, "tokens", ".txt");
        if (modelPath == null || tokensPath == null) {
            env.error("Kokoro model files are incomplete", null);
            return null;
        }
        OfflineTtsKokoroModelConfig.Builder kokoroBuilder = OfflineTtsKokoroModelConfig.builder()
                .setModel(modelPath)
                .setTokens(tokensPath);
        if (info.voicesFile != null && !info.voicesFile.isBlank()) {
            kokoroBuilder.setVoices(new File(modelDir, info.voicesFile).getAbsolutePath());
        }
        if (info.dataDir != null && !info.dataDir.isBlank()) {
            kokoroBuilder.setDataDir(new File(modelDir, info.dataDir).getAbsolutePath());
        }
        if (info.lexiconFiles != null && !info.lexiconFiles.isEmpty()) {
            kokoroBuilder.setLexicon(joinPaths(modelDir, info.lexiconFiles));
        }
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setKokoro(kokoroBuilder.build())
                .setNumThreads(threadsFor(info.getEngineType(), false))
                .setDebug(false)
                .build();
        return buildConfigWithRuleFsts(modelConfig, modelDir, info.ruleFsts);
    }

    private OfflineTtsConfig buildMatchaConfig(File modelDir, TtsModelInfo info, String vocoderPath) {
        String modelPath = resolveModelFile(modelDir, info.modelFiles);
        String tokensPath = findRequiredFile(modelDir, "tokens", ".txt");
        if (modelPath == null || tokensPath == null) {
            env.error("Matcha model files are incomplete", null);
            return null;
        }
        OfflineTtsMatchaModelConfig.Builder matchaBuilder = OfflineTtsMatchaModelConfig.builder()
                .setAcousticModel(modelPath)
                .setTokens(tokensPath);
        if (vocoderPath != null && !vocoderPath.isBlank()) {
            matchaBuilder.setVocoder(vocoderPath);
        }
        if (info.dataDir != null && !info.dataDir.isBlank()) {
            matchaBuilder.setDataDir(new File(modelDir, info.dataDir).getAbsolutePath());
        }
        if (info.lexiconFiles != null && !info.lexiconFiles.isEmpty()) {
            matchaBuilder.setLexicon(joinPaths(modelDir, info.lexiconFiles));
        }
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setMatcha(matchaBuilder.build())
                .setNumThreads(threadsFor(info.getEngineType(), false))
                .setDebug(false)
                .build();
        return buildConfigWithRuleFsts(modelConfig, modelDir, info.ruleFsts);
    }

    private OfflineTtsConfig buildZipVoiceConfig(File modelDir, TtsModelInfo info) {
        String tokensPath = findRequiredFile(modelDir, "tokens", ".txt");
        String encoderPath = resolveZipVoiceFile(modelDir, info.modelFiles, "text_encoder");
        String decoderPath = resolveZipVoiceFile(modelDir, info.modelFiles, "fm_decoder");
        String vocoderPath = resolveZipVoiceVocoder(modelDir);
        if (tokensPath == null || encoderPath == null || decoderPath == null || vocoderPath == null) {
            env.error("ZipVoice model files are incomplete", null);
            return null;
        }
        OfflineTtsZipVoiceModelConfig.Builder zipVoiceBuilder = OfflineTtsZipVoiceModelConfig.builder()
                .setTokens(tokensPath)
                .setEncoder(encoderPath)
                .setDecoder(decoderPath)
                .setVocoder(vocoderPath)
                .setFeatScale(0.1f)
                .setTShift(0.5f)
                .setTargetRms(0.1f)
                .setGuidanceScale(1.0f);
        File dataDir = info.dataDir != null && !info.dataDir.isBlank()
                ? new File(modelDir, info.dataDir)
                : new File(modelDir, "espeak-ng-data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            zipVoiceBuilder.setDataDir(dataDir.getAbsolutePath());
        }
        File pinyinRaw = new File(modelDir, "pinyin.raw");
        if (pinyinRaw.exists() && pinyinRaw.isFile()) {
            zipVoiceBuilder.setLexicon(pinyinRaw.getAbsolutePath());
        } else if (info.lexiconFiles != null && !info.lexiconFiles.isEmpty()) {
            zipVoiceBuilder.setLexicon(joinPaths(modelDir, info.lexiconFiles));
        }
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setZipvoice(zipVoiceBuilder.build())
                .setNumThreads(threadsFor(info.getEngineType(), true))
                .setDebug(false)
                .build();
        return buildConfigWithRuleFsts(modelConfig, modelDir, info.ruleFsts);
    }

    private OfflineTtsConfig buildVitsMetadataConfig(File modelDir, TtsModelInfo info) {
        String modelPath = resolveModelFile(modelDir, info.modelFiles);
        String tokensPath = findRequiredFile(modelDir, "tokens", ".txt");
        if (modelPath == null || tokensPath == null) {
            env.error("VITS model files are incomplete", null);
            return null;
        }
        OfflineTtsVitsModelConfig.Builder vitsBuilder = OfflineTtsVitsModelConfig.builder()
                .setModel(modelPath)
                .setTokens(tokensPath);
        if (info.dataDir != null && !info.dataDir.isBlank()) {
            vitsBuilder.setDataDir(new File(modelDir, info.dataDir).getAbsolutePath());
        }
        if (info.lexiconFiles != null && !info.lexiconFiles.isEmpty()) {
            vitsBuilder.setLexicon(joinPaths(modelDir, info.lexiconFiles));
        } else {
            File lexicon = findFile(modelDir, "lexicon", ".txt");
            if (lexicon != null) {
                vitsBuilder.setLexicon(lexicon.getAbsolutePath());
            }
        }
        File dictDir = new File(modelDir, "dict");
        if (dictDir.exists() && dictDir.isDirectory()) {
            vitsBuilder.setDictDir(dictDir.getAbsolutePath());
        }
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsBuilder.build())
                .setNumThreads(threadsFor(info.getEngineType(), false))
                .setDebug(false)
                .build();
        List<String> effectiveRuleFsts = info.ruleFsts;
        String autoRuleFstsPath = null;
        if (effectiveRuleFsts == null || effectiveRuleFsts.isEmpty()) {
            autoRuleFstsPath = autoDetectRuleFsts(modelDir);
        }
        return buildConfigWithRuleFsts(modelConfig, modelDir, effectiveRuleFsts, autoRuleFstsPath);
    }

    private OfflineTtsConfig buildLegacyConfig(File modelDir, String modelName) {
        OfflineTtsVitsModelConfig.Builder vitsBuilder = OfflineTtsVitsModelConfig.builder();
        if ("MeloTTS".equals(modelName)) {
            return buildMeloTtsConfig(modelDir, vitsBuilder);
        }
        return buildPiperConfig(modelDir, vitsBuilder);
    }

    private OfflineTtsConfig buildPiperConfig(File modelDir, OfflineTtsVitsModelConfig.Builder vitsBuilder) {
        File modelFile = findFile(modelDir, ".onnx", "zh_CN-huayan-medium");
        File tokensFile = findFile(modelDir, "tokens", ".txt");
        if (modelFile == null || tokensFile == null) {
            env.error("PiperTTS model files are incomplete, dir: " + modelDir.getAbsolutePath(), null);
            return null;
        }
        vitsBuilder.setModel(modelFile.getAbsolutePath());
        vitsBuilder.setTokens(tokensFile.getAbsolutePath());
        File dataDir = new File(modelDir, "espeak-ng-data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            vitsBuilder.setDataDir(dataDir.getAbsolutePath());
        }
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsBuilder.build())
                .setNumThreads(threadsFor("piper", false))
                .setDebug(false)
                .build();
        return OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .setMaxNumSentences(1)
                .build();
    }

    private OfflineTtsConfig buildMeloTtsConfig(File modelDir, OfflineTtsVitsModelConfig.Builder vitsBuilder) {
        File modelFile = findFile(modelDir, "model", ".onnx");
        File tokensFile = findFile(modelDir, "tokens", ".txt");
        File lexiconFile = findFile(modelDir, "lexicon", ".txt");
        if (modelFile == null || tokensFile == null) {
            env.error("MeloTTS model files are incomplete, dir: " + modelDir.getAbsolutePath(), null);
            return null;
        }
        vitsBuilder.setModel(modelFile.getAbsolutePath());
        vitsBuilder.setTokens(tokensFile.getAbsolutePath());
        if (lexiconFile != null) {
            vitsBuilder.setLexicon(lexiconFile.getAbsolutePath());
        }
        File dictDir = new File(modelDir, "dict");
        if (dictDir.exists() && dictDir.isDirectory()) {
            vitsBuilder.setDictDir(dictDir.getAbsolutePath());
        }
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsBuilder.build())
                .setNumThreads(threadsFor("melo", false))
                .setDebug(false)
                .build();
        StringBuilder ruleFsts = new StringBuilder();
        File dateFst = findFile(modelDir, "date", ".fst");
        File numberFst = findFile(modelDir, "number", ".fst");
        if (dateFst != null) {
            ruleFsts.append(dateFst.getAbsolutePath());
        }
        if (numberFst != null) {
            if (ruleFsts.length() > 0) {
                ruleFsts.append(",");
            }
            ruleFsts.append(numberFst.getAbsolutePath());
        }
        OfflineTtsConfig.Builder configBuilder = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .setMaxNumSentences(1);
        if (!ruleFsts.isEmpty()) {
            configBuilder.setRuleFsts(ruleFsts.toString());
        }
        return configBuilder.build();
    }

    private OfflineTtsConfig buildConfigWithRuleFsts(OfflineTtsModelConfig modelConfig, File modelDir, List<String> ruleFsts) {
        return buildConfigWithRuleFsts(modelConfig, modelDir, ruleFsts, null);
    }

    private OfflineTtsConfig buildConfigWithRuleFsts(OfflineTtsModelConfig modelConfig, File modelDir, List<String> ruleFsts, String autoRuleFstsPath) {
        OfflineTtsConfig.Builder configBuilder = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .setMaxNumSentences(1);
        if (ruleFsts != null && !ruleFsts.isEmpty()) {
            configBuilder.setRuleFsts(joinPaths(modelDir, ruleFsts));
        } else if (autoRuleFstsPath != null && !autoRuleFstsPath.isBlank()) {
            configBuilder.setRuleFsts(autoRuleFstsPath);
        }
        return configBuilder.build();
    }

    private String autoDetectRuleFsts(File modelDir) {
        StringBuilder result = new StringBuilder();
        String[] fstNames = {"date", "number", "phone", "new_heteronym"};
        for (String name : fstNames) {
            File fst = findFile(modelDir, name, ".fst");
            if (fst != null) {
                if (result.length() > 0) {
                    result.append(",");
                }
                result.append(fst.getAbsolutePath());
            }
        }
        return result.length() > 0 ? result.toString() : null;
    }

    private String resolveModelFile(File modelDir, List<String> modelFiles) {
        if (modelFiles == null || modelFiles.isEmpty()) {
            return null;
        }
        for (String candidate : modelFiles) {
            if (candidate.endsWith(".pack.onnx")) {
                File file = new File(modelDir, candidate);
                if (file.exists()) {
                    return file.getAbsolutePath();
                }
            }
        }
        for (String candidate : modelFiles) {
            if (candidate.equals("model.onnx")) {
                File file = new File(modelDir, candidate);
                if (file.exists()) {
                    return file.getAbsolutePath();
                }
            }
        }
        for (String candidate : modelFiles) {
            File file = new File(modelDir, candidate);
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private String resolveZipVoiceFile(File modelDir, List<String> modelFiles, String prefix) {
        if (modelFiles != null) {
            for (String candidate : modelFiles) {
                if (candidate.toLowerCase().startsWith(prefix.toLowerCase())) {
                    File file = new File(modelDir, candidate);
                    if (file.exists()) {
                        return file.getAbsolutePath();
                    }
                }
            }
        }
        File fallback = findFile(modelDir, prefix, ".onnx");
        return fallback != null ? fallback.getAbsolutePath() : null;
    }

    private String resolveZipVoiceVocoder(File modelDir) {
        File vocoder = new File(modelDir, "vocos_24khz.onnx");
        if (vocoder.exists()) {
            return vocoder.getAbsolutePath();
        }
        File fallback = findFile(modelDir, "vocoder", ".onnx");
        return fallback != null ? fallback.getAbsolutePath() : null;
    }

    private String joinPaths(File baseDir, List<String> relativePaths) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < relativePaths.size(); i++) {
            if (i > 0) {
                result.append(",");
            }
            result.append(new File(baseDir, relativePaths.get(i)).getAbsolutePath());
        }
        return result.toString();
    }

    private String resolveLegacyModelName() {
        Path modelPath = config.getTtsModelPath();
        if (modelPath != null && modelPath.getFileName() != null) {
            return modelPath.getFileName().toString();
        }
        return "PiperTTS";
    }

    private int threadsFor(String engineType, boolean zipVoice) {
        return resourcePolicy.sherpaTtsThreads(engineType, zipVoice);
    }

    private File findFile(File dir, String keyword, String extension) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String fileName = file.getName().toLowerCase();
            if (file.isFile() && fileName.contains(keyword.toLowerCase()) && fileName.endsWith(extension)) {
                return file;
            }
        }
        return null;
    }

    private String findRequiredFile(File dir, String keyword, String extension) {
        File found = findFile(dir, keyword, extension);
        return found != null ? found.getAbsolutePath() : null;
    }

    public record ResolvedConfig(OfflineTtsConfig config, boolean zipVoice) {
    }
}
