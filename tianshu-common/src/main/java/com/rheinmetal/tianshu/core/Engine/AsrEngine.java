package com.rheinmetal.tianshu.core.Engine;

import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.ModelSettings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private OnlineRecognizer recognizer;
    private boolean isTransducer = false;
    private String modelDirPath;

    public AsrEngine(IGameEnvironment env) {
        this.env = env;
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

    public void initialize(String modelDir) {
        env.info("初始化 ASR 引擎，模型目录: " + modelDir);
        this.modelDirPath = modelDir;
        File dir = new File(modelDir);

        try {
            File encoder = findModelFile(dir, "encoder", ".onnx");
            File decoder = findModelFile(dir, "decoder", ".onnx");
            File joiner = findModelFile(dir, "joiner", ".onnx");
            File tokensFile = findModelFile(dir, "tokens", ".txt");

            if (encoder == null || decoder == null || tokensFile == null) {
                env.error("找不到必要的模型文件！", null);
                return;
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
            recognizer = new OnlineRecognizer(configBuilder.build());
            env.info("ASR 引擎初始化成功 (Transducer=" + isTransducer + ")");
        } catch (Throwable t) {
            env.error("ASR 引擎创建失败，请检查模型文件是否损坏", t);
        }
    }

    public StreamingSession createStreamingSession() {
        if (recognizer == null) {
            env.error("ASR 引擎未初始化", null);
            return null;
        }
        return new StreamingSession(recognizer.createStream());
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
        if (recognizer == null || session == null || pcmData == null || pcmData.length == 0) return "";
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
            while (recognizer.isReady(session.stream)) {
                recognizer.decode(session.stream);
            }
            return recognizer.getResult(session.stream).getText();
        } finally {
            session.lock.unlock();
        }
    }

    public boolean isEndpoint(StreamingSession session) {
        if (recognizer == null || session == null) return false;
        session.lock.lock();
        try {
            return recognizer.isEndpoint(session.stream);
        } finally {
            session.lock.unlock();
        }
    }

    public void reset(StreamingSession session) {
        if (recognizer == null || session == null) return;
        session.lock.lock();
        try {
            recognizer.reset(session.stream);
        } finally {
            session.lock.unlock();
        }
    }

    public String recognizeComplete(byte[] fullAudio) {
        if (recognizer == null) return "";
        OnlineStream tempStream = recognizer.createStream();

        float[] samples = new float[fullAudio.length / 2];
        for (int i = 0; i != samples.length; ++i) {
            short low = (short) fullAudio[2 * i];
            short high = (short) fullAudio[2 * i + 1];
            int s = (high << 8) + low;
            samples[i] = (float) s / 32768;
        }

        tempStream.acceptWaveform(samples, 16000);
        tempStream.acceptWaveform(new float[(int) (0.8 * 16000)], 16000);

        while (recognizer.isReady(tempStream)) {
            recognizer.decode(tempStream);
        }

        String result = recognizer.getResult(tempStream).getText();
        tempStream.release();
        return result;
    }

    public String forceFlush(StreamingSession session) {
        if (recognizer == null || session == null) return "";
        session.lock.lock();
        try {
            session.stream.acceptWaveform(new float[(int) (0.3 * 16000)], 16000);
            while (recognizer.isReady(session.stream)) {
                recognizer.decode(session.stream);
            }
            String result = recognizer.getResult(session.stream).getText();
            recognizer.reset(session.stream);
            return result;
        } finally {
            session.lock.unlock();
        }
    }

    public void shutdown() {
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
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
