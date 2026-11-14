package com.rheinmetal.tianshu.text;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切句参数只能有一份来源。
 *
 * <p>语音链路的 AX 输出层与 TTS 层各写过一组数字（{@code 12/24/180} 与 {@code 24/90/180}），结果是"一句话"
 * 的粒度取决于谁先切。现在唯一的构造入口是 {@link SentenceSegmenter#spokenDialogue()}，这份用例守住它：
 * 只要有人又在别处 {@code new SentenceSegmenter(...)}，这里就红。</p>
 */
class SentenceSegmenterConfigTest {

    @Test
    void onlyTheFactoryBuildsSegmentersInMainSources() throws IOException {
        Path mainSources = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(mainSources),
                "需要在模块目录（tianshu-common）下运行测试：" + mainSources.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("SentenceSegmenter.java"))
                    .forEach(path -> {
                        try {
                            if (Files.readString(path, StandardCharsets.UTF_8).contains("new SentenceSegmenter(")) {
                                offenders.add(path.toString());
                            }
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    });
        }

        assertTrue(offenders.isEmpty(),
                "切句参数又出现了副本，请改用 SentenceSegmenter.spokenDialogue()：" + offenders);
    }
}
