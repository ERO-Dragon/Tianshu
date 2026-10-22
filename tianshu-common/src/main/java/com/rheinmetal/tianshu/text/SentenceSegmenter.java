package com.rheinmetal.tianshu.text;

import java.util.Locale;
import java.util.Set;

/**
 * 句子切分器。
 *
 * <p>语音链路的两层（AX 主对话输出层、TTS 合成层）用<b>同一份参数</b>：见
 * {@link #SPOKEN_MIN_LENGTH} / {@link #SPOKEN_PREFERRED_LENGTH} / {@link #SPOKEN_MAX_LENGTH} 与
 * {@link #spokenDialogue()}。任何一层要改行为就改这里，不要在调用点另写一组数字——两套参数会让"一句话"的
 * 粒度取决于谁先切，听感会飘。</p>
 *
 * <p>切分规则（按优先级）：</p>
 * <ol>
 *   <li>强标点（{@code 。！？!?}）与换行：立即切；</li>
 *   <li>软标点（逗号、顿号、分号、冒号）：长度 ≥ {@code minLength} 才是候选，≥ {@code preferredLength} 才切；</li>
 *   <li>长度达到 {@code maxLength}：强制切（优先最后一个软标点，其次最后一个空白）。</li>
 * </ol>
 */
public final class SentenceSegmenter {

    // ------------------------------------------------------------ 语音对话的统一参数（唯一来源）

    /** 软标点最早可切长度：阻止"嗯，"这类 2~3 字碎块被单独切成一条语音。 */
    public static final int SPOKEN_MIN_LENGTH = 12;

    /** 软标点优先切分长度：约一句话的自然呼吸（中文约 12~15 秒），超过它遇到逗号就切。 */
    public static final int SPOKEN_PREFERRED_LENGTH = 60;

    /** 硬上限：没有任何标点也要在这一长度内切断，避免一条长音频拖到无法及时打断。 */
    public static final int SPOKEN_MAX_LENGTH = 120;

    /** 语音对话（AX 输出层 + TTS 层）唯一允许的构造入口。 */
    public static SentenceSegmenter spokenDialogue() {
        return new SentenceSegmenter(SPOKEN_MIN_LENGTH, SPOKEN_PREFERRED_LENGTH, SPOKEN_MAX_LENGTH);
    }

    // ------------------------------------------------------------ 实例

    private static final Set<String> LATIN_ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "fig", "no", "vol"
    );

    private final int minLength;
    private final int preferredLength;
    private final int maxLength;

    public SentenceSegmenter(int minLength, int preferredLength, int maxLength) {
        this.minLength = Math.max(8, minLength);
        this.maxLength = Math.max(this.minLength + 1, maxLength);
        this.preferredLength = Math.min(Math.max(this.minLength + 1, preferredLength), this.maxLength);
    }

    public boolean shouldFlush(String text) {
        return nextBoundary(text).shouldFlush();
    }

    public SegmentBoundary nextBoundary(String text) {
        if (text == null || text.isBlank()) {
            return SegmentBoundary.none();
        }
        String value = text.stripLeading();
        if (value.isBlank()) {
            return SegmentBoundary.none();
        }
        int boundary = findBestBoundary(value);
        if (boundary > 0) {
            return new SegmentBoundary(true, boundary);
        }
        if (value.length() >= maxLength) {
            return new SegmentBoundary(true, findForcedBoundary(value));
        }
        return SegmentBoundary.none();
    }

    private int findBestBoundary(String text) {
        int preferredBoundary = -1;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (!isBoundaryCandidate(text, i, current)) {
                continue;
            }
            int end = consumeTrailingClosers(text, i + 1);
            if (isStrongBoundary(current) || current == '.') {
                return end;
            }
            if (end < minLength) {
                continue;
            }
            preferredBoundary = end;
            if (end >= preferredLength) {
                return end;
            }
        }
        return text.length() >= preferredLength ? preferredBoundary : -1;
    }

    private int findForcedBoundary(String text) {
        int bestSoftBoundary = -1;
        int limit = Math.min(text.length(), maxLength);
        for (int i = minLength; i < limit; i++) {
            char current = text.charAt(i);
            if (isSoftBoundary(current)) {
                bestSoftBoundary = consumeTrailingClosers(text, i + 1);
            }
        }
        if (bestSoftBoundary > 0) {
            return bestSoftBoundary;
        }
        for (int i = limit - 1; i >= minLength; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return limit;
    }

    private boolean isBoundaryCandidate(String text, int index, char current) {
        if (isStrongBoundary(current)) {
            return true;
        }
        if (isSoftBoundary(current)) {
            return true;
        }
        if (current == '.') {
            return isSafePeriodBoundary(text, index);
        }
        if (current == '\n') {
            return true;
        }
        return false;
    }

    private boolean isSafePeriodBoundary(String text, int index) {
        if (isDecimalPoint(text, index)) {
            return false;
        }
        String token = previousLatinToken(text, index).toLowerCase(Locale.ROOT);
        if (LATIN_ABBREVIATIONS.contains(token)) {
            return false;
        }
        if (token.length() == 1 && Character.isUpperCase(token.charAt(0))) {
            return false;
        }
        return true;
    }

    private String previousLatinToken(String text, int index) {
        int start = index - 1;
        while (start >= 0) {
            char value = text.charAt(start);
            if (!(Character.isLetter(value) || value == '.')) {
                break;
            }
            start--;
        }
        return text.substring(start + 1, index);
    }

    private boolean isDecimalPoint(String text, int index) {
        return index > 0
                && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1));
    }

    private boolean isStrongBoundary(char value) {
        return value == '。' || value == '！' || value == '!' || value == '？' || value == '?';
    }

    private boolean isSoftBoundary(char value) {
        return value == '；' || value == ';' || value == '，' || value == ',' || value == '、' || value == '：' || value == ':' || value == '\n';
    }

    private int consumeTrailingClosers(String text, int index) {
        int result = index;
        while (result < text.length()) {
            char value = text.charAt(result);
            if (value == '”' || value == '’' || value == '"' || value == '\'' || value == ')' || value == '）' || value == ']' || value == '】') {
                result++;
                continue;
            }
            break;
        }
        return result;
    }

    public record SegmentBoundary(boolean shouldFlush, int endIndex) {
        public SegmentBoundary {
            if (!shouldFlush) {
                endIndex = -1;
            }
        }

        public static SegmentBoundary none() {
            return new SegmentBoundary(false, -1);
        }
    }
}
