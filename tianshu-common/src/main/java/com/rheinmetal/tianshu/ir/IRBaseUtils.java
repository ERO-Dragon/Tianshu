package com.rheinmetal.tianshu.ir;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IRBaseUtils {

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    public static String[] reverseLookupArray = EMPTY_STRING_ARRAY;
    public static String[] localizedNameArray = EMPTY_STRING_ARRAY;
    public static Map<String, Integer> forwardLookupMap = Map.of();

    public static String[][] primaryAliasTokensArray = new String[0][];
    public static String[][] fallbackAliasTokensArray = new String[0][];

    private IRBaseUtils() {
    }

    public static int buildMapping(Map<String, List<String>> rawDict) {
        LinkedHashMap<String, Integer> idMap = new LinkedHashMap<>(Math.max(16, rawDict.size()));
        int itemCount = rawDict.size();
        String[] reverse = new String[itemCount];
        String[] names = new String[itemCount];
        String[][] primaryTokens = new String[itemCount][];
        String[][] fallbackTokens = new String[itemCount][];
        int index = 0;
        for (Map.Entry<String, List<String>> entry : rawDict.entrySet()) {
            idMap.put(entry.getKey(), index);
            reverse[index] = entry.getKey();
            List<String> aliases = entry.getValue();
            
            if (aliases == null || aliases.isEmpty()) {
                names[index] = "";
                primaryTokens[index] = EMPTY_STRING_ARRAY;
                fallbackTokens[index] = EMPTY_STRING_ARRAY;
            } else {
                names[index] = aliases.get(0);
                // 索引 0 必定是主语言（如中文），直接切词存入主轨道
                primaryTokens[index] = tokenize(aliases.get(0)); 
                
                // 索引 1 必定是英文兜底，切词存入副轨道
                if (aliases.size() > 1) {
                    fallbackTokens[index] = tokenize(aliases.get(1)); 
                } else {
                    fallbackTokens[index] = EMPTY_STRING_ARRAY;
                }
            }
            index++;
        }
        reverseLookupArray = reverse;
        localizedNameArray = names;
        forwardLookupMap = idMap;
        primaryAliasTokensArray = primaryTokens;
        fallbackAliasTokensArray = fallbackTokens;
        return index;
    }

    public static int fnv1a32(String str) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < str.length(); i++) {
            hash ^= str.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
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
        return c == '_' || c == '-' || c == ':' || c == '/' || c == '\\' || c == '，' || c == '。' || c == ',' || c == '.';
    }
}
