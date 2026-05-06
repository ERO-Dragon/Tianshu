package com.rheinmetal.tianshu.function.ir.core;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.HashMap;
import java.util.Map;

public final class PinyinUtil {

    private static final char[][] PINYIN_TABLE;
    private static final HanyuPinyinOutputFormat PINYIN_OUTPUT_FORMAT = createOutputFormat();

    static {
        int capacity = 65536;
        char[][] table = new char[capacity][];
        String raw =
            "43014:ai;43015:ai;43016:ai;43017:ai;43018:ai;43019:ai;43020:ai;43021:ai;43022:ai;43023:ai;" +
            "43024:ai;43025:ai;43026:ai;43027:ai;43028:ai;43029:ai;43030:ai;43031:ai;43032:ai;43033:ai;" +
            "44001:ba;44002:ba;44003:ba;44004:ba;44005:ba;44006:ba;44007:ba;44008:ba;44009:ba;44010:ba;" +
            "44011:ba;44012:ba;44013:ba;44014:ba;44015:ba;44016:ba;44017:ba;44018:ba;44019:ba;44020:ba;" +
            "44021:ba;44022:ba;44023:ba;44024:ba;44025:ba;44026:ba;44027:ba;44028:ba;44029:ba;44030:ba;" +
            "44031:ba;44032:ba;44033:ba;44034:ba;44035:ba;44036:ba;44037:ba;44038:ba;44039:ba;44040:ba;" +
            "44041:ba;44042:ba;44043:ba;44044:ba;44045:ba;44046:ba;44047:ba;44048:ba;44049:ba;44050:ba";
        Map<Integer, char[]> tempMap = new HashMap<>(256);
        int pos = 0;
        while (pos < raw.length()) {
            int colon = raw.indexOf(':', pos);
            if (colon < 0) break;
            int codePoint = Integer.parseInt(raw.substring(pos, colon));
            int semi = raw.indexOf(';', colon + 1);
            if (semi < 0) semi = raw.length();
            String pinyin = raw.substring(colon + 1, semi);
            tempMap.put(codePoint, pinyin.toCharArray());
            pos = semi + 1;
        }
        for (Map.Entry<Integer, char[]> e : tempMap.entrySet()) {
            int idx = e.getKey();
            if (idx >= 0 && idx < capacity) {
                table[idx] = e.getValue();
            }
        }
        PINYIN_TABLE = table;
    }

    private static final Map<Character, char[]> UNCOMMON_MAP = buildUncommonMap();

    private static Map<Character, char[]> buildUncommonMap() {
        Map<Character, char[]> map = new HashMap<>(2048);
        addRange(map, '\u4E00',
            "yi,ding,kao,er,liao,er,er,jian,shang,xia,bu,yu,zhao,wan,shisi,shi,shi,shi,shi,jiong," +
            "bing,ji,qian,zi,kao,qi,qi,yan,ba,jiu,jiu,yi,jiu,yu,qian,wu,zhao,zhao,zhao,zhao,zhao," +
            "qian,zhong,zhong,zhong,zhong,zhong,zhong,qian,zhong,zhong,zhong,qian,zhong,zhong,zhong,zhong,zhong," +
            "qian,zhong,zhong,zhong,qian,zhong,zhong,zhong,zhong,zhong,qian,zhong,zhong,zhong,zhong,zhong");
        addRange(map, '\u4E40',
            "dan,zhao,zhao,zhao,zhao,zhao,qian,zhong,zhong,zhong,zhong,zhong,zhong,qian,zhong,zhong");
        addMapping(map, '\u63A7', "kong");
        addMapping(map, '\u5236', "zhi");
        addMapping(map, '\u5668', "qi");
        addMapping(map, '\u94BB', "zuan");
        addMapping(map, '\u77F3', "shi");
        addMapping(map, '\u5251', "jian");
        addMapping(map, '\u6CE5', "ni");
        addMapping(map, '\u571F', "tu");
        return map;
    }

    private static void addRange(Map<Character, char[]> map, char start, String csv) {
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                map.put((char)(start + i), parts[i].toCharArray());
            }
        }
    }

    private static void addMapping(Map<Character, char[]> map, char key, String pinyin) {
        map.put(key, pinyin.toCharArray());
    }

    public static String toPinyinWithoutTone(char c) {
        if (c >= 0x4E00 && c <= 0x9FFF) {
            char[] cached = UNCOMMON_MAP.get(c);
            if (cached != null) {
                return new String(cached);
            }
        }
        if (c >= 0 && c < PINYIN_TABLE.length) {
            char[] cached = PINYIN_TABLE[c];
            if (cached != null) {
                return new String(cached);
            }
        }
        if (c >= 0x4E00 && c <= 0x9FFF) {
            String fromLibrary = lookupFromLibrary(c);
            if (!fromLibrary.isEmpty()) {
                return fromLibrary;
            }
        }
        if (c >= 'A' && c <= 'Z') {
            return String.valueOf((char)(c + 32));
        }
        if (c >= 'a' && c <= 'z') {
            return String.valueOf(c);
        }
        return "";
    }

    private static HanyuPinyinOutputFormat createOutputFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }

    private static String lookupFromLibrary(char c) {
        try {
            String[] candidates = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_OUTPUT_FORMAT);
            if (candidates == null || candidates.length == 0) {
                return "";
            }
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isEmpty()) {
                    return candidate;
                }
            }
        } catch (BadHanyuPinyinOutputFormatCombination ignored) {
        }
        return "";
    }
}
