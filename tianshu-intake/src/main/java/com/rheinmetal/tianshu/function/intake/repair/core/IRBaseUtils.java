package com.rheinmetal.tianshu.function.intake.repair.core;

import java.util.Arrays;

/**
 * Pure text utilities of the IR index: tokenizing a name, building gram keys, hashing.
 *
 * <p>This class no longer holds the dictionary. The mapping, aliases and inverted pool live in one
 * {@link IRIndex} owned by {@link IRCommandService}, which is what makes a rebuild a single reference
 * swap instead of a set of static fields a second instance could observe half-written.</p>
 */
public final class IRBaseUtils {

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    /** A pure homophone may differ in at most one tone in three; more than that is a different phrase. */
    private static final double MAX_ZERO_OVERLAP_TONE_MISMATCH_RATIO = 1.0D / 3.0D;

    private IRBaseUtils() {
    }

    public static int fnv1a32(String str) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < str.length(); i++) {
            hash ^= str.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * Whether a repair proposal has enough evidence to be more than a pinyin guess.
     *
     * <p>A shared written character is strong evidence: an ASR error usually keeps part of the word
     * ({@code 铁活板们} for {@code 铁活板门}). A zero-shared proposal is a pure homophone and is accepted only
     * when it is the same syllables in the same order (no material prefix added, no trailing word dropped) and
     * the tones agree closely; otherwise ordinary speech ({@code 伙伴们在哪}) can be rewritten into an item
     * name that merely sounds similar — or into a longer name with an extra qualifier.</p>
     */
    static boolean hasPlausibleRepairEvidence(String sourceSlice, String target) {
        if (sourceSlice == null || target == null || sourceSlice.isEmpty() || target.isEmpty()) {
            return false;
        }
        if (sharedHanCharacter(sourceSlice, target)) {
            return true;
        }
        if (!isAllHan(sourceSlice) || !isAllHan(target) || sourceSlice.length() != target.length()) {
            return false;
        }
        // Same syllables in the same order: an insertion ("铁" not heard) or a deletion ("在" swallowed)
        // is not a homophone repair even when the LCS is high.
        String sourcePinyin = joinTokens(tokenize(sourceSlice));
        String targetPinyin = joinTokens(tokenize(target));
        if (!sourcePinyin.equals(targetPinyin)) {
            return false;
        }
        int compared = 0;
        int mismatches = 0;
        for (int index = 0; index < sourceSlice.length(); index++) {
            String sourceTone = PinyinUtil.toPinyinWithToneNumber(sourceSlice.charAt(index));
            String targetTone = PinyinUtil.toPinyinWithToneNumber(target.charAt(index));
            if (sourceTone.isEmpty() || targetTone.isEmpty()) {
                continue;
            }
            compared++;
            if (!sourceTone.equals(targetTone)) {
                mismatches++;
            }
        }
        return compared > 0 && (double) mismatches / compared <= MAX_ZERO_OVERLAP_TONE_MISMATCH_RATIO;
    }

    static boolean sharedHanCharacter(String first, String second) {
        for (int firstIndex = 0; firstIndex < first.length(); firstIndex++) {
            char candidate = first.charAt(firstIndex);
            if (!isCjkUnifiedIdeograph(candidate)) {
                continue;
            }
            for (int secondIndex = 0; secondIndex < second.length(); secondIndex++) {
                if (candidate == second.charAt(secondIndex)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAllHan(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!isCjkUnifiedIdeograph(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    public static String[] tokenize(String name) {
        if (name == null || name.isEmpty()) {
            return EMPTY_STRING_ARRAY;
        }

        String[] buffer = new String[Math.max(4, name.length())];
        int size = 0;
        int length = name.length();
        int i = 0;

        while (i < length) {
            char c = name.charAt(i);

            if (Character.isWhitespace(c) || isSeparator(c)) {
                i++;
                continue;
            }

            if (isAsciiLetter(c)) {
                int start = i;
                i++;
                while (i < length) {
                    char current = name.charAt(i);
                    if (!isAsciiLetter(current)) {
                        break;
                    }
                    char previous = name.charAt(i - 1);
                    if (Character.isLowerCase(previous) && Character.isUpperCase(current)) {
                        break;
                    }
                    i++;
                }
                buffer = ensureStringCapacity(buffer, size + 1);
                buffer[size++] = toAsciiLowerCase(name, start, i);
                continue;
            }

            if (isCjkUnifiedIdeograph(c)) {
                String pinyin = PinyinUtil.toPinyinWithoutTone(c);
                if (!pinyin.isEmpty()) {
                    buffer = ensureStringCapacity(buffer, size + 1);
                    buffer[size++] = pinyin;
                }
                i++;
                continue;
            }

            if (Character.isDigit(c)) {
                int start = i;
                i++;
                while (i < length && Character.isDigit(name.charAt(i))) {
                    i++;
                }
                buffer = ensureStringCapacity(buffer, size + 1);
                buffer[size++] = name.substring(start, i);
                continue;
            }

            i++;
        }

        if (size == 0) {
            return EMPTY_STRING_ARRAY;
        }
        return Arrays.copyOf(buffer, size);
    }

    public static String joinTokens(String[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return "";
        }
        int total = 0;
        for (String token : tokens) {
            total += token.length();
        }
        StringBuilder builder = new StringBuilder(total);
        for (String token : tokens) {
            builder.append(token);
        }
        return builder.toString();
    }

    public static String buildGram(String[] tokens, int start, int gramSize) {
        int totalLength = 0;
        for (int i = 0; i < gramSize; i++) {
            totalLength += tokens[start + i].length() + 1;
        }
        StringBuilder builder = new StringBuilder(totalLength);
        for (int i = 0; i < gramSize; i++) {
            if (i > 0) {
                builder.append('_');
            }
            builder.append(tokens[start + i]);
        }
        return builder.toString();
    }

    private static String[] ensureStringCapacity(String[] source, int capacity) {
        if (capacity <= source.length) {
            return source;
        }
        int newCapacity = source.length + (source.length >> 1) + 1;
        if (newCapacity < capacity) {
            newCapacity = capacity;
        }
        return Arrays.copyOf(source, newCapacity);
    }

    private static String toAsciiLowerCase(String source, int start, int end) {
        boolean needsLowerCase = false;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                needsLowerCase = true;
                break;
            }
        }
        if (!needsLowerCase) {
            return source.substring(start, end);
        }
        char[] chars = new char[end - start];
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            chars[i - start] = (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
        }
        return new String(chars);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isCjkUnifiedIdeograph(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static boolean isSeparator(char c) {
        return c == '_' || c == '-' || c == ':' || c == '/' || c == '\\' || c == ',' || c == '.' || c == '，' || c == '。';
    }
}