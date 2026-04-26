package com.rheinmetal.tianshu.core.Engine;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;
import com.rheinmetal.tianshu.model.ModelSettings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class AsrEngine {

    public static final class StreamingSession {
        private final OnlineStream stream;
        private final ReentrantLock lock = new ReentrantLock();

        private StreamingSession(OnlineStream stream) {
            this.stream = stream;
        }
    }

    private final IGameEnvironment env;
    private OnlineRecognizer onlineRecognizer;
    private OfflineRecognizer offlineRecognizer;
    private boolean isTransducer = false;
    private boolean isOffline = false;
    private String modelDirPath;

    public AsrEngine(IGameEnvironment env) {
        this.env = env;
    }

    public boolean initialize(AsrModelInfo modelInfo, Path modelDir) throws ModelFilesMissingException {
        env.info("初始化 ASR 引擎，模型: " + modelInfo.name + "，目录: " + modelDir);
        this.modelDirPath = modelDir.toAbsolutePath().toString();

        List<String> missing = new ArrayList<>();
        for (String file : modelInfo.getAllRequiredFiles()) {
            if (!Files.isRegularFile(modelDir.resolve(file))) {
                missing.add(file);
            }
        }
        if (!missing.isEmpty()) {
            throw new ModelFilesMissingException(modelInfo.name, missing);
        }

        try {
            String encoderPath = null;
            String decoderPath = null;
            String joinerPath = null;
            String tokensPath = null;
            String singleModelPath = null;

            for (String fileName : modelInfo.modelFiles) {
                String lower = fileName.toLowerCase();
                Path resolved = modelDir.resolve(fileName);
                if (lower.contains("encoder")) encoderPath = resolved.toAbsolutePath().toString();
                else if (lower.contains("decoder")) decoderPath = resolved.toAbsolutePath().toString();
                else if (lower.contains("joiner")) joinerPath = resolved.toAbsolutePath().toString();
                else if (lower.endsWith(".onnx") || lower.endsWith(".pt") || lower.endsWith(".bin")) {
                    if (singleModelPath == null) singleModelPath = resolved.toAbsolutePath().toString();
                }
            }

            if (modelInfo.lexiconFiles != null) {
                for (String fileName : modelInfo.lexiconFiles) {
                    String lower = fileName.toLowerCase();
                    Path resolved = modelDir.resolve(fileName);
                    if (lower.contains("tokens")) tokensPath = resolved.toAbsolutePath().toString();
                }
            }

            env.info("使用模型文件:");
            if (encoderPath != null) env.info("  Encoder: " + encoderPath);
            if (decoderPath != null) env.info("  Decoder: " + decoderPath);
            if (joinerPath != null) env.info("  Joiner: " + joinerPath);
            if (singleModelPath != null) env.info("  Model: " + singleModelPath);
            if (tokensPath != null) env.info("  Tokens: " + tokensPath);

            boolean isSingleFile = encoderPath == null && decoderPath == null && singleModelPath != null;

            switch (modelInfo.getModelType()) {
                case AsrModelInfo.TYPE_TRANSDUCER -> {
                    if (encoderPath == null || decoderPath == null || tokensPath == null) {
                        env.error("TRANSDUCER 模型缺少必要文件 (encoder/decoder/tokens)", null);
                        return false;
                    }
                    isTransducer = true;
                    isOffline = false;
                    env.info("检测到 Transducer 流式模型");
                    initOnlineTransducer(encoderPath, decoderPath, joinerPath, tokensPath, modelInfo, modelDir);
                }
                case AsrModelInfo.TYPE_PARAFORMER,
                        AsrModelInfo.TYPE_CTC,
                        AsrModelInfo.TYPE_WENET,
                        AsrModelInfo.TYPE_TELESPEECH -> {
                    if (isSingleFile) {
                        if (tokensPath == null) {
                            env.error(modelInfo.getModelType() + " 单文件模型缺少 tokens 文件", null);
                            return false;
                        }
                        isTransducer = false;
                        isOffline = true;
                        env.info("检测到 " + modelInfo.getModelType() + " 离线单文件模型，使用 OfflineRecognizer");
                        initOfflineParaformer(singleModelPath, tokensPath);
                    } else {
                        if (encoderPath == null || decoderPath == null || tokensPath == null) {
                            env.error(modelInfo.getModelType() + " 模型缺少必要文件 (encoder/decoder/tokens)", null);
                            return false;
                        }
                        isTransducer = false;
                        isOffline = false;
                        env.info("检测到 " + modelInfo.getModelType() + " 模型，按 Paraformer 流式方式初始化");
                        initOnlineParaformer(encoderPath, decoderPath, tokensPath);
                    }
                }
                case AsrModelInfo.TYPE_WHISPER,
                        AsrModelInfo.TYPE_NEMO,
                        AsrModelInfo.TYPE_SENSEVOICE,
                        AsrModelInfo.TYPE_MOONSHINE,
                        AsrModelInfo.TYPE_DOLPHIN,
                        AsrModelInfo.TYPE_QWEN3_ASR,
                        AsrModelInfo.TYPE_FUNASR_NANO,
                        AsrModelInfo.TYPE_OTHER -> {
                    env.error("当前 ASR 引擎尚未适配该模型类型: " + modelInfo.getModelType() + "，后续版本将支持", null);
                    return false;
                }
                default -> {
                    env.error("不支持的 ASR 模型类型: " + modelInfo.getModelType(), null);
                    return false;
                }
            }

            env.info("ASR 引擎初始化成功 (modelType=" + modelInfo.getModelType() + ", Transducer=" + isTransducer + ", Offline=" + isOffline + ")");
        } catch (Throwable t) {
            env.error("ASR 引擎创建失败，请检查模型文件是否损坏", t);
            return false;
        }
        return true;
    }

    private void initOnlineTransducer(String encoderPath, String decoderPath, String joinerPath, String tokensPath, AsrModelInfo modelInfo, Path modelDir) {
        OnlineTransducerModelConfig.Builder transBuilder = OnlineTransducerModelConfig.builder()
                .setEncoder(encoderPath)
                .setDecoder(decoderPath);
        if (joinerPath != null) {
            transBuilder.setJoiner(joinerPath);
        }
        OnlineTransducerModelConfig transducer = transBuilder.build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokensPath)
                .setNumThreads(2)
                .setDebug(true)
                .build();

        OnlineRecognizerConfig.Builder configBuilder = OnlineRecognizerConfig.builder();
        if (modelInfo.supportHotwords) {
            Path hotwordsFile = modelDir.resolve("hotwords.txt");
            if (Files.exists(hotwordsFile)) {
                env.info("检测到热词文件: " + hotwordsFile);
                configBuilder.setDecodingMethod("modified_beam_search")
                        .setHotwordsFile(hotwordsFile.toAbsolutePath().toString());
                ModelSettings.AsrSettings settings = ModelSettings.loadAsrSettings(modelDir);
                configBuilder.setHotwordsScore((float) settings.hotwordsScore);
                env.info("热词已启用，解码方式: modified_beam_search，分数: " + settings.hotwordsScore);
            } else {
                configBuilder.setDecodingMethod("greedy_search");
                env.info("模型支持热词但未检测到 hotwords.txt，使用 greedy_search");
            }
        } else {
            configBuilder.setDecodingMethod("greedy_search");
            env.info("模型不支持热词，使用 greedy_search");
        }
        configBuilder.setOnlineModelConfig(modelConfig);
        onlineRecognizer = new OnlineRecognizer(configBuilder.build());
    }

    private void initOnlineParaformer(String encoderPath, String decoderPath, String tokensPath) {
        OnlineParaformerModelConfig paraformer = OnlineParaformerModelConfig.builder()
                .setEncoder(encoderPath)
                .setDecoder(decoderPath)
                .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setParaformer(paraformer)
                .setTokens(tokensPath)
                .setNumThreads(2)
                .setDebug(true)
                .build();

        OnlineRecognizerConfig.Builder configBuilder = OnlineRecognizerConfig.builder();
        configBuilder.setDecodingMethod("greedy_search");
        configBuilder.setOnlineModelConfig(modelConfig);
        onlineRecognizer = new OnlineRecognizer(configBuilder.build());
    }

    private void initOfflineParaformer(String modelPath, String tokensPath) {
        OfflineParaformerModelConfig paraformer = OfflineParaformerModelConfig.builder()
                .setModel(modelPath)
                .build();

        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setParaformer(paraformer)
                .setTokens(tokensPath)
                .setNumThreads(2)
                .setDebug(true)
                .build();

        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .build();
        offlineRecognizer = new OfflineRecognizer(config);
    }

    public boolean initialize(String modelDir) {
        env.info("ASR 引擎兼容模式初始化，模型目录: " + modelDir);
        this.modelDirPath = modelDir;
        File dir = new File(modelDir);

        try {
            File encoder = findModelFile(dir, "encoder", ".onnx");
            File decoder = findModelFile(dir, "decoder", ".onnx");
            File joiner = findModelFile(dir, "joiner", ".onnx");
            File tokensFile = findModelFile(dir, "tokens", ".txt");

            if (tokensFile == null) {
                env.error("找不到 tokens 文件！", null);
                return false;
            }

            File singleModel = null;
            if (encoder == null || decoder == null) {
                singleModel = findAnyModelFile(dir);
            }

            if (singleModel != null && encoder == null) {
                isTransducer = false;
                isOffline = true;
                env.info("检测到单文件离线模型: " + singleModel.getName());
                initOfflineParaformer(singleModel.getAbsolutePath(), tokensFile.getAbsolutePath());
                env.info("ASR 引擎初始化成功 (Offline=true)");
                return true;
            }

            if (encoder == null || decoder == null) {
                env.error("找不到必要的模型文件！", null);
                return false;
            }

            env.info("使用模型文件:");
            env.info("  Encoder: " + encoder.getAbsolutePath());
            env.info("  Decoder: " + decoder.getAbsolutePath());
            if (joiner != null) {
                env.info("  Joiner: " + joiner.getAbsolutePath());
            }
            env.info("  Tokens: " + tokensFile.getAbsolutePath());

            OnlineModelConfig modelConfig;
            OnlineRecognizerConfig.Builder configBuilder = OnlineRecognizerConfig.builder();

            if (joiner != null) {
                isTransducer = true;
                isOffline = false;
                env.info("检测到 Transducer 流式模型");
                OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                        .setEncoder(encoder.getAbsolutePath())
                        .setDecoder(decoder.getAbsolutePath())
                        .setJoiner(joiner.getAbsolutePath())
                        .build();

                modelConfig = OnlineModelConfig.builder()
                        .setTransducer(transducer)
                        .setTokens(tokensFile.getAbsolutePath())
                        .setNumThreads(2)
                        .setDebug(true)
                        .build();

                Path hotwordsFile = dir.toPath().resolve("hotwords.txt");
                if (Files.exists(hotwordsFile)) {
                    env.info("检测到热词文件: " + hotwordsFile);
                    configBuilder.setDecodingMethod("modified_beam_search")
                            .setHotwordsFile(hotwordsFile.toAbsolutePath().toString());
                    ModelSettings.AsrSettings settings = ModelSettings.loadAsrSettings(dir.toPath());
                    configBuilder.setHotwordsScore((float) settings.hotwordsScore);
                    env.info("热词已启用，解码方式: modified_beam_search，分数: " + settings.hotwordsScore);
                } else {
                    configBuilder.setDecodingMethod("greedy_search");
                    env.info("未检测到热词文件，使用 greedy_search");
                }
            } else {
                isTransducer = false;
                isOffline = false;
                env.info("检测到 Paraformer 流式模型");
                OnlineParaformerModelConfig paraformer = OnlineParaformerModelConfig.builder()
                        .setEncoder(encoder.getAbsolutePath())
                        .setDecoder(decoder.getAbsolutePath())
                        .build();

                modelConfig = OnlineModelConfig.builder()
                        .setParaformer(paraformer)
                        .setTokens(tokensFile.getAbsolutePath())
                        .setNumThreads(2)
                        .setDebug(true)
                        .build();

                configBuilder.setDecodingMethod("greedy_search");
            }

            configBuilder.setOnlineModelConfig(modelConfig);
            onlineRecognizer = new OnlineRecognizer(configBuilder.build());
            env.info("ASR 引擎初始化成功 (Transducer=" + isTransducer + ", Offline=" + isOffline + ")");
        } catch (Throwable t) {
            env.error("ASR 引擎创建失败，请检查模型文件是否损坏", t);
            return false;
        }
        return true;
    }

    private File findAnyModelFile(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".onnx") && !name.contains("encoder") && !name.contains("decoder") && !name.contains("joiner")) {
                return file;
            }
        }
        return null;
    }

    private File findModelFile(File dir, String keyword, String extension) {
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

    public boolean isOffline() {
        return isOffline;
    }

    public StreamingSession createStreamingSession() {
        if (isOffline) {
            env.warn("离线模型不支持流式识别，请使用 PTT 模式");
            return null;
        }
        if (onlineRecognizer == null) {
            env.error("ASR 引擎未初始化", null);
            return null;
        }
        return new StreamingSession(onlineRecognizer.createStream());
    }

    public void releaseStreamingSession(StreamingSession session) {
        if (session == null) {
            return;
        }
        session.lock.lock();
        try {
            session.stream.release();
        } finally {
            session.lock.unlock();
        }
    }

    public String feedAudio(StreamingSession session, byte[] pcmData) {
        if (onlineRecognizer == null || session == null || pcmData == null || pcmData.length == 0) return "";
        session.lock.lock();
        try {
            float[] samples = new float[pcmData.length / 2];
            for (int i = 0; i != samples.length; ++i) {
                short low = (short) pcmData[2 * i];
                short high = (short) pcmData[2 * i + 1];
                int s = (high << 8) + low;
                samples[i] = (float) s / 32768;
            }
            session.stream.acceptWaveform(samples, 16000);
            while (onlineRecognizer.isReady(session.stream)) {
                onlineRecognizer.decode(session.stream);
            }
            return onlineRecognizer.getResult(session.stream).getText();
        } finally {
            session.lock.unlock();
        }
    }

    public boolean isEndpoint(StreamingSession session) {
        if (onlineRecognizer == null || session == null) return false;
        session.lock.lock();
        try {
            return onlineRecognizer.isEndpoint(session.stream);
        } finally {
            session.lock.unlock();
        }
    }

    public void reset(StreamingSession session) {
        if (onlineRecognizer == null || session == null) return;
        session.lock.lock();
        try {
            onlineRecognizer.reset(session.stream);
        } finally {
            session.lock.unlock();
        }
    }

    public String recognizeComplete(byte[] fullAudio) {
        float[] samples = new float[fullAudio.length / 2];
        for (int i = 0; i != samples.length; ++i) {
            short low = (short) fullAudio[2 * i];
            short high = (short) fullAudio[2 * i + 1];
            int s = (high << 8) + low;
            samples[i] = (float) s / 32768;
        }

        if (isOffline && offlineRecognizer != null) {
            OfflineStream tempStream = offlineRecognizer.createStream();
            tempStream.acceptWaveform(samples, 16000);
            offlineRecognizer.decode(tempStream);
            String result = offlineRecognizer.getResult(tempStream).getText();
            tempStream.release();
            return result;
        }

        if (onlineRecognizer == null) return "";
        OnlineStream tempStream = onlineRecognizer.createStream();
        tempStream.acceptWaveform(samples, 16000);
        tempStream.acceptWaveform(new float[(int) (0.8 * 16000)], 16000);
        while (onlineRecognizer.isReady(tempStream)) {
            onlineRecognizer.decode(tempStream);
        }
        String result = onlineRecognizer.getResult(tempStream).getText();
        tempStream.release();
        return result;
    }

    public String forceFlush(StreamingSession session) {
        if (onlineRecognizer == null || session == null) return "";
        session.lock.lock();
        try {
            session.stream.acceptWaveform(new float[(int) (0.3 * 16000)], 16000);
            while (onlineRecognizer.isReady(session.stream)) {
                onlineRecognizer.decode(session.stream);
            }
            String result = onlineRecognizer.getResult(session.stream).getText();
            onlineRecognizer.reset(session.stream);
            return result;
        } finally {
            session.lock.unlock();
        }
    }

    public void shutdown() {
        if (onlineRecognizer != null) {
            onlineRecognizer.release();
            onlineRecognizer = null;
        }
        if (offlineRecognizer != null) {
            offlineRecognizer.release();
            offlineRecognizer = null;
        }
        env.info("ASR 引擎已安全关闭");
    }

    public boolean isTransducer() {
        return isTransducer;
    }

    public boolean isHotwordsActive() {
        if (!isTransducer || modelDirPath == null) return false;
        return Files.exists(Path.of(modelDirPath).resolve("hotwords.txt"));
    }

    public static boolean detectTransducer(Path modelDir) {
        if (!Files.isDirectory(modelDir)) return false;
        File dir = modelDir.toFile();
        return findFileStatic(dir, "joiner", ".onnx") != null;
    }

    private static File findFileStatic(File dir, String keyword, String extension) {
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
