package com.rheinmetal.tianshu.core.engine;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.config.ModelSettings;
import com.rheinmetal.tianshu.utils.PathUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

public class TtsEngine {
    private OfflineTts tts;
    private boolean initialized = false;
    private int sampleRate;
    private float speed = 1.0f;
    private int speakerId = 0;
    private String modelDirPath;

    public TtsEngine() {
    }

    public void setSpeed(float speed) {
        this.speed = Math.max(0.5f, Math.min(2.0f, speed));
    }

    public void setSpeakerId(int speakerId) {
        this.speakerId = Math.max(0, speakerId);
    }

    public void initialize(String modelDir) {
        Tianshu.LOGGER.info("初始化 TTS 引擎，模型目录: {}", modelDir);
        this.modelDirPath = modelDir;

        File dir = new File(modelDir);
        if (!dir.exists() || !dir.isDirectory()) {
            Tianshu.LOGGER.error("TTS 模型目录不存在: {}", modelDir);
            return;
        }

        File safeDir = PathUtils.getSafeModelDir(dir);
        if (safeDir == null) {
            Tianshu.LOGGER.error("获取安全 TTS 模型目录失败");
            return;
        }

        String modelName = resolveModelName();
        Tianshu.LOGGER.info("TTS 模型类型: {}", modelName);

        try {
            OfflineTtsConfig config = buildConfig(safeDir, modelName);
            if (config == null) {
                Tianshu.LOGGER.error("无法构建 TTS 配置，未知模型类型: {}", modelName);
                return;
            }

            tts = new OfflineTts(config);
            sampleRate = tts.getSampleRate();

            ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(java.nio.file.Path.of(modelDir));
            this.speed = (float) settings.speed;
            this.speakerId = settings.speakerId;

            initialized = true;
            Tianshu.LOGGER.info("TTS 引擎初始化成功，采样率: {}Hz，说话人数: {}", sampleRate, tts.getNumSpeakers());
        } catch (Throwable t) {
            Tianshu.LOGGER.error("TTS 引擎初始化失败", t);
        }
    }

    private String resolveModelName() {
        Path modelPath = Config.getTtsModelPath();
        if (modelPath != null && modelPath.getFileName() != null) {
            return modelPath.getFileName().toString();
        }
        return "PiperTTS";
    }

    private OfflineTtsConfig buildConfig(File modelDir, String modelName) {
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
            Tianshu.LOGGER.error("PiperTTS 模型文件不完整，目录: {}", modelDir.getAbsolutePath());
            return null;
        }

        vitsBuilder.setModel(modelFile.getAbsolutePath());
        vitsBuilder.setTokens(tokensFile.getAbsolutePath());

        File dataDir = new File(modelDir, "espeak-ng-data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            vitsBuilder.setDataDir(dataDir.getAbsolutePath());
        }

        Tianshu.LOGGER.info("PiperTTS 配置: model={}, tokens={}", modelFile.getName(), tokensFile.getName());

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
            Tianshu.LOGGER.error("MeloTTS 模型文件不完整，目录: {}", modelDir.getAbsolutePath());
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

        Tianshu.LOGGER.info("MeloTTS 配置: model={}, tokens={}, lexicon={}",
                modelFile.getName(), tokensFile.getName(),
                lexiconFile != null ? lexiconFile.getName() : "N/A");

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
        if (!initialized || tts == null) {
            Tianshu.LOGGER.error("TTS 引擎未初始化");
            return;
        }

        Tianshu.LOGGER.info("TTS 开始合成: {} (speed={}, speaker={})", text, speed, speakerId);

        try {
            tts.generateWithCallback(text, speakerId, speed, samples -> {
                byte[] pcm = floatSamplesToPcm16(samples);
                if (pcm.length > 0) {
                    onAudioChunk.accept(pcm);
                }
                return 1;
            });
            Tianshu.LOGGER.info("TTS 合成完成: {}", text);
        } catch (Exception e) {
            Tianshu.LOGGER.error("TTS 合成失败: {}", text, e);
        }
    }

    public void synthesizeFull(String text, Consumer<byte[]> onAudio) {
        if (!initialized || tts == null) {
            Tianshu.LOGGER.error("TTS 引擎未初始化");
            return;
        }

        Tianshu.LOGGER.info("TTS 开始完整合成: {} (speed={}, speaker={})", text, speed, speakerId);

        try {
            var audio = tts.generate(text, speakerId, speed);
            float[] samples = audio.getSamples();
            byte[] pcm = floatSamplesToPcm16(samples);
            onAudio.accept(pcm);
            Tianshu.LOGGER.info("TTS 完整合成完成，音频长度: {} bytes", pcm.length);
        } catch (Exception e) {
            Tianshu.LOGGER.error("TTS 完整合成失败: {}", text, e);
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

    public int getSampleRate() {
        return sampleRate;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void shutdown() {
        if (tts != null) {
            tts.release();
            tts = null;
        }
        initialized = false;
        Tianshu.LOGGER.info("TTS 引擎已关闭");
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
}
