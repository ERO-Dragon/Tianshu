package com.rheinmetal.tianshu.core.Engine;

import com.k2fsa.sherpa.onnx.*;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelSettings;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.model.tts.moss.MossTtsService;
import com.rheinmetal.tianshu.utils.PathUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class TtsEngine {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private OfflineTts tts;
    private MossTtsService mossTtsService;
    private boolean isMossEngine = false;
    private boolean isZipVoiceEngine = false;
    private volatile boolean interrupted = false;
    private boolean initialized = false;
    private int sampleRate;
    private float speed = 1.0f;
    private int speakerId = 0;
    private Path voiceSamplePath;
    private String modelDirPath;

    public TtsEngine(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
    }

    public void setSpeed(float speed) {
        this.speed = Math.max(0.1f, Math.min(5.0f, speed));
    }

    public void setSpeakerId(int speakerId) {
        this.speakerId = Math.max(0, speakerId);
    }

    public void setVoiceSamplePath(Path voiceSamplePath) {
        this.voiceSamplePath = voiceSamplePath;
    }

    public void interrupt() {
        interrupted = true;
    }

    public void resetInterrupt() {
        interrupted = false;
    }

    public void initialize(String modelDir) {
        env.info("初始化 TTS 引擎，模型目录: " + modelDir);
        this.modelDirPath = modelDir;

        File dir = new File(modelDir);
        if (!dir.exists() || !dir.isDirectory()) {
            env.error("TTS 模型目录不存在: " + modelDir, null);
            return;
        }

        File safeDir = PathUtils.getSafeModelDir(dir);
        if (safeDir == null) {
            env.error("获取安全 TTS 模型目录失败", null);
            return;
        }

        String modelName = resolveModelName();
        env.info("TTS 模型类型: " + modelName);

        try {
            OfflineTtsConfig ttsConfig = buildLegacyConfig(safeDir, modelName);
            if (ttsConfig == null) {
                env.error("无法构建 TTS 配置，未知模型类型: " + modelName, null);
                return;
            }

            applyConfig(ttsConfig, modelDir);
        } catch (Throwable t) {
            env.error("TTS 引擎初始化失败", t);
        }
    }

    public void initialize(String modelDir, TtsModelInfo info) {
        initializeInternal(modelDir, info, null);
    }

    public void initialize(String modelDir, TtsModelInfo info, String vocoderPath) {
        initializeInternal(modelDir, info, vocoderPath);
    }

    private void initializeInternal(String modelDir, TtsModelInfo info, String vocoderPath) {
        String label = vocoderPath != null ? "元数据驱动+声码器" : "元数据驱动";
        env.info("初始化 TTS 引擎（" + label + "），模型目录: " + modelDir);
        this.modelDirPath = modelDir;

        String engineType = info.getEngineType();
        env.info("TTS 引擎类型: " + engineType + "，模型: " + info.name);

        if ("moss".equals(engineType)) {
            initializeMossEngine(modelDir);
            return;
        }

        File dir = new File(modelDir);
        if (!dir.exists() || !dir.isDirectory()) {
            env.error("TTS 模型目录不存在: " + modelDir, null);
            return;
        }

        File safeDir = PathUtils.getSafeModelDir(dir);
        if (safeDir == null) {
            env.error("获取安全 TTS 模型目录失败", null);
            return;
        }

        try {
            OfflineTtsConfig ttsConfig = buildMetadataConfig(safeDir, info, engineType, vocoderPath);
            if (ttsConfig == null) {
                env.error("无法构建 TTS 配置，引擎: " + engineType, null);
                return;
            }

            applyConfig(ttsConfig, modelDir);
        } catch (Throwable t) {
            env.error("TTS 引擎初始化失败", t);
        }
    }

    private void initializeMossEngine(String modelDir) {
        try {
            Path modelRootDir = Path.of(modelDir);
            if (!Files.exists(modelRootDir)) {
                Files.createDirectories(modelRootDir);
            }

            HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
            mossTtsService = new MossTtsService(env, downloader, modelRootDir);
            mossTtsService.init();

            sampleRate = mossTtsService.getSampleRate();
            isMossEngine = true;
            initialized = true;

            env.info("MOSS-TTS 引擎初始化成功，采样率: " + sampleRate + "Hz");
        } catch (Throwable t) {
            env.error("MOSS-TTS 引擎初始化失败", t);
            mossTtsService = null;
            isMossEngine = false;
        }
    }
    private void applyConfig(OfflineTtsConfig ttsConfig, String modelDir) throws Exception {
        tts = new OfflineTts(ttsConfig);
        sampleRate = tts.getSampleRate();
        isMossEngine = false;
        isZipVoiceEngine = ttsConfig.getModel().getZipvoice() != null;

        ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(Path.of(modelDir));
        this.speed = (float) settings.speed;
        this.speakerId = settings.speakerId;

        initialized = true;
        env.info("TTS 引擎初始化成功，采样率: " + sampleRate + "Hz，说话人数: " + tts.getNumSpeakers());
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
            env.error("Kokoro 模型文件不完整", null);
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

        env.info("Kokoro 配置: model=" + modelPath + ", tokens=" + tokensPath +
                ", voices=" + (info.voicesFile != null ? info.voicesFile : "N/A"));

        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setKokoro(kokoroBuilder.build())
                .setNumThreads(2)
                .setDebug(false)
                .build();

        return buildConfigWithRuleFsts(modelConfig, modelDir, info.ruleFsts);
    }

    private OfflineTtsConfig buildMatchaConfig(File modelDir, TtsModelInfo info, String vocoderPath) {
        String modelPath = resolveModelFile(modelDir, info.modelFiles);
        String tokensPath = findRequiredFile(modelDir, "tokens", ".txt");

        if (modelPath == null || tokensPath == null) {
            env.error("Matcha 模型文件不完整", null);
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

        env.info("Matcha 配置: acousticModel=" + modelPath + ", tokens=" + tokensPath +
                ", vocoder=" + (vocoderPath != null ? vocoderPath : "N/A"));

        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setMatcha(matchaBuilder.build())
                .setNumThreads(2)
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
            env.error("ZipVoice 模型文件不完整", null);
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

        env.info("ZipVoice 配置: encoder=" + encoderPath + ", decoder=" + decoderPath +
                ", tokens=" + tokensPath + ", vocoder=" + vocoderPath);

        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setZipvoice(zipVoiceBuilder.build())
                .setNumThreads(2)
                .setDebug(false)
                .build();

        return buildConfigWithRuleFsts(modelConfig, modelDir, info.ruleFsts);
    }

    private OfflineTtsConfig buildVitsMetadataConfig(File modelDir, TtsModelInfo info) {
        String modelPath = resolveModelFile(modelDir, info.modelFiles);
        String tokensPath = findRequiredFile(modelDir, "tokens", ".txt");

        if (modelPath == null || tokensPath == null) {
            env.error("VITS 模型文件不完整", null);
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
                env.info("VITS 自动检测到 lexicon: " + lexicon.getName());
            }
        }

        File dictDir = new File(modelDir, "dict");
        if (dictDir.exists() && dictDir.isDirectory()) {
            vitsBuilder.setDictDir(dictDir.getAbsolutePath());
            env.info("VITS 自动检测到 dict 目录: " + dictDir.getAbsolutePath());
        }

        env.info("VITS 配置: model=" + modelPath + ", tokens=" + tokensPath);

        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsBuilder.build())
                .setNumThreads(2)
                .setDebug(false)
                .build();

        List<String> effectiveRuleFsts = info.ruleFsts;
        String autoRuleFstsPath = null;
        if ((effectiveRuleFsts == null || effectiveRuleFsts.isEmpty())) {
            autoRuleFstsPath = autoDetectRuleFsts(modelDir);
            if (autoRuleFstsPath != null) {
                env.info("VITS 自动检测到 ruleFsts: " + autoRuleFstsPath);
            }
        }
        return buildConfigWithRuleFsts(modelConfig, modelDir, effectiveRuleFsts, autoRuleFstsPath);
    }

    private String autoDetectRuleFsts(File modelDir) {
        StringBuilder sb = new StringBuilder();
        String[] fstNames = {"date", "number", "phone", "new_heteronym"};
        for (String name : fstNames) {
            File fst = findFile(modelDir, name, ".fst");
            if (fst != null) {
                if (sb.length() > 0) sb.append(",");
                sb.append(fst.getAbsolutePath());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
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

    private String resolveModelFile(File modelDir, List<String> modelFiles) {
        if (modelFiles == null || modelFiles.isEmpty()) return null;

        for (String candidate : modelFiles) {
            if (candidate.endsWith(".pack.onnx")) {
                File f = new File(modelDir, candidate);
                if (f.exists()) return f.getAbsolutePath();
            }
        }

        for (String candidate : modelFiles) {
            if (candidate.equals("model.onnx")) {
                File f = new File(modelDir, candidate);
                if (f.exists()) return f.getAbsolutePath();
            }
        }

        for (String candidate : modelFiles) {
            File f = new File(modelDir, candidate);
            if (f.exists()) return f.getAbsolutePath();
        }

        return null;
    }

    private String resolveZipVoiceFile(File modelDir, List<String> modelFiles, String prefix) {
        if (modelFiles == null) return null;
        for (String candidate : modelFiles) {
            if (candidate.toLowerCase().startsWith(prefix.toLowerCase())) {
                File f = new File(modelDir, candidate);
                if (f.exists()) return f.getAbsolutePath();
            }
        }
        File fallback = findFile(modelDir, prefix, ".onnx");
        return fallback != null ? fallback.getAbsolutePath() : null;
    }

    private String resolveZipVoiceVocoder(File modelDir) {
        File vocoder = new File(modelDir, "vocos_24khz.onnx");
        if (vocoder.exists()) return vocoder.getAbsolutePath();
        File fallback = findFile(modelDir, "vocoder", ".onnx");
        return fallback != null ? fallback.getAbsolutePath() : null;
    }

    private String joinPaths(File baseDir, List<String> relativePaths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relativePaths.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(new File(baseDir, relativePaths.get(i)).getAbsolutePath());
        }
        return sb.toString();
    }

    private String resolveModelName() {
        Path modelPath = config.getTtsModelPath();
        if (modelPath != null && modelPath.getFileName() != null) {
            return modelPath.getFileName().toString();
        }
        return "PiperTTS";
    }

    private OfflineTtsConfig buildLegacyConfig(File modelDir, String modelName) {
        OfflineTtsVitsModelConfig.Builder vitsBuilder = OfflineTtsVitsModelConfig.builder();
        if ("MeloTTS".equals(modelName)) {
            return buildMeloTtsConfig(modelDir, vitsBuilder);
        } else {
            return buildPiperConfig(modelDir, vitsBuilder);
        }
    }

    private OfflineTtsConfig buildPiperConfig(File modelDir, OfflineTtsVitsModelConfig.Builder vitsBuilder) {
        File modelFile = findFile(modelDir, ".onnx", "zh_CN-huayan-medium");
        File tokensFile = findFile(modelDir, "tokens", ".txt");

        if (modelFile == null || tokensFile == null) {
            env.error("PiperTTS 模型文件不完整，目录: " + modelDir.getAbsolutePath(), null);
            return null;
        }

        vitsBuilder.setModel(modelFile.getAbsolutePath());
        vitsBuilder.setTokens(tokensFile.getAbsolutePath());

        File dataDir = new File(modelDir, "espeak-ng-data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            vitsBuilder.setDataDir(dataDir.getAbsolutePath());
        }

        env.info("PiperTTS 配置: model=" + modelFile.getName() + ", tokens=" + tokensFile.getName());

        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsBuilder.build())
                .setNumThreads(2)
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
            env.error("MeloTTS 模型文件不完整，目录: " + modelDir.getAbsolutePath(), null);
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

        env.info("MeloTTS 配置: model=" + modelFile.getName() + ", tokens=" + tokensFile.getName() +
                ", lexicon=" + (lexiconFile != null ? lexiconFile.getName() : "N/A"));

        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsBuilder.build())
                .setNumThreads(2)
                .setDebug(false)
                .build();

        StringBuilder ruleFsts = new StringBuilder();
        File dateFst = findFile(modelDir, "date", ".fst");
        File numberFst = findFile(modelDir, "number", ".fst");
        if (dateFst != null) {
            ruleFsts.append(dateFst.getAbsolutePath());
        }
        if (numberFst != null) {
            if (ruleFsts.length() > 0) ruleFsts.append(",");
            ruleFsts.append(numberFst.getAbsolutePath());
        }

        OfflineTtsConfig.Builder configBuilder = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .setMaxNumSentences(1);

        if (ruleFsts.length() > 0) {
            configBuilder.setRuleFsts(ruleFsts.toString());
        }

        return configBuilder.build();
    }

    public void synthesizeSpeech(String text, Consumer<byte[]> onAudioChunk) {
        if (!initialized) {
            env.error("TTS 引擎未初始化", null);
            return;
        }

        interrupted = false;

        if (isMossEngine) {
            synthesizeMoss(text, onAudioChunk);
            return;
        }

        if (tts == null) {
            env.error("TTS 引擎未初始化", null);
            return;
        }

        if (isZipVoiceEngine) {
            synthesizeZipVoice(text, onAudioChunk);
            return;
        }

        env.info("TTS 开始合成: " + text + " (speed=" + speed + ", speaker=" + speakerId + ")");

        try {
            tts.generateWithCallback(text, speakerId, speed, samples -> {
                if (interrupted) return 0;
                byte[] pcm = floatSamplesToPcm16(samples);
                if (pcm.length > 0) {
                    onAudioChunk.accept(pcm);
                }
                return 1;
            });
            if (interrupted) {
                env.info("TTS 合成被中断: " + text);
            } else {
                env.info("TTS 合成完成: " + text);
            }
        } catch (Exception e) {
            env.error("TTS 合成失败: " + text, e);
        }
    }

    public void synthesizeFull(String text, Consumer<byte[]> onAudio) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!initialized) {
            env.error("TTS 引擎未初始化", null);
            return;
        }

        interrupted = false;

        if (isMossEngine) {
            synthesizeMoss(text, onAudio);
            return;
        }

        if (tts == null) {
            env.error("TTS 引擎未初始化", null);
            return;
        }

        if (isZipVoiceEngine) {
            synthesizeZipVoice(text, onAudio);
            return;
        }

        env.info("TTS 开始完整合成: " + text + " (speed=" + speed + ", speaker=" + speakerId + ")");

        try {
            var audio = tts.generate(text, speakerId, speed);
            float[] samples = audio.getSamples();
            byte[] pcm = floatSamplesToPcm16(samples);
            onAudio.accept(pcm);
            env.info("TTS 完整合成完成，音频长度: " + pcm.length + " bytes");
        } catch (Exception e) {
            env.error("TTS 完整合成失败: " + text, e);
        }
    }

    private void synthesizeMoss(String text, Consumer<byte[]> onAudioChunk) {
        if (mossTtsService == null) {
            env.error("MOSS-TTS 引擎未初始化", null);
            return;
        }

        env.info("MOSS-TTS 开始合成: " + text);

        try {
            if (interrupted) {
                env.info("MOSS-TTS 合成被中断（合成前）: " + text);
                return;
            }
            List<List<Integer>> promptAudioCodes = null;
            if (voiceSamplePath != null && Files.exists(voiceSamplePath)) {
                env.info("MOSS-TTS 使用参考音频: " + voiceSamplePath);
                promptAudioCodes = mossTtsService.encodePromptAudioCodes(voiceSamplePath);
            }
            if (interrupted) {
                env.info("MOSS-TTS 合成被中断（编码后）: " + text);
                return;
            }
            float[][] channels = mossTtsService.synthesizeToWaveform(text, promptAudioCodes);
            if (interrupted) {
                env.info("MOSS-TTS 合成被中断: " + text);
                return;
            }
            byte[] pcm = floatSamplesToPcm16(channels);
            if (pcm.length > 0) {
                onAudioChunk.accept(pcm);
            }
            env.info("MOSS-TTS 合成完成: " + text);
        } catch (Exception e) {
            env.error("MOSS-TTS 合成失败: " + text, e);
        }
    }

    private void synthesizeZipVoice(String text, Consumer<byte[]> onAudioChunk) {
        if (tts == null) {
            env.error("ZipVoice 引擎未初始化", null);
            return;
        }

        env.info("ZipVoice 开始合成: " + text + " (speed=" + speed + ")");

        try {
            GenerationConfig generationConfig = new GenerationConfig();
            generationConfig.setSpeed(speed);

            if (voiceSamplePath != null && Files.exists(voiceSamplePath)) {
                WaveReader reader = new WaveReader(voiceSamplePath.toString());
                generationConfig.setReferenceAudio(reader.getSamples());
                generationConfig.setReferenceSampleRate(reader.getSampleRate());
                generationConfig.setReferenceText("");
                generationConfig.setNumSteps(5);
                env.info("ZipVoice 使用参考音频: " + voiceSamplePath + ", sampleRate=" + reader.getSampleRate());
            }

            tts.generateWithConfigAndCallback(text, generationConfig, samples -> {
                if (interrupted) return 0;
                byte[] pcm = floatSamplesToPcm16(samples);
                if (pcm.length > 0) {
                    onAudioChunk.accept(pcm);
                }
                return 1;
            });
            if (interrupted) {
                env.info("ZipVoice 合成被中断: " + text);
            } else {
                env.info("ZipVoice 合成完成: " + text);
            }
        } catch (Exception e) {
            env.error("ZipVoice 合成失败: " + text, e);
        }
    }

    private byte[] floatSamplesToPcm16(float[] samples) {
        if (samples == null || samples.length == 0) return new byte[0];
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float clamped = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            short val = (short) (clamped * 32767.0f);
            pcm[2 * i] = (byte) (val & 0xFF);
            pcm[2 * i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        return pcm;
    }

    private byte[] floatSamplesToPcm16(float[][] channels) {
        if (channels == null || channels.length == 0 || channels[0].length == 0) return new byte[0];
        float[] mono = channels[0];
        return floatSamplesToPcm16(mono);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void shutdown() {
        if (mossTtsService != null) {
            try {
                mossTtsService.close();
            } catch (Exception ignored) {
            }
            mossTtsService = null;
            isMossEngine = false;
        }
        if (tts != null) {
            tts.release();
            tts = null;
        }
        isZipVoiceEngine = false;
        initialized = false;
        env.info("TTS 引擎已关闭");
    }

    private File findFile(File dir, String keyword, String extension) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
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
}