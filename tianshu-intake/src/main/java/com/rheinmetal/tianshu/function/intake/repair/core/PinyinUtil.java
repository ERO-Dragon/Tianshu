package com.rheinmetal.tianshu.function.intake.repair.core;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * Pinyin of one character, without tone.
 *
 * <p>pinyin4j is the single source of truth. The previous build carried two hand-written tables beside
 * it: a 65536-entry table indexed by code units that the tokenizer never asks about, and a range table
 * whose entries were shifted by three positions from the first row ("七" reading as "er", "上" as "bu",
 * "下" as "yu"), which produced false homophones between unrelated item names. Hard-coded language data
 * also goes stale the moment a name changes, so it is gone rather than repaired.</p>
 */
public final class PinyinUtil {

    private static final HanyuPinyinOutputFormat PINYIN_OUTPUT_FORMAT = createOutputFormat();
    private static final HanyuPinyinOutputFormat PINYIN_TONE_FORMAT = createToneFormat();

    private PinyinUtil() {
    }

    public static String toPinyinWithoutTone(char c) {
        String fromLibrary = lookupFromLibrary(c, PINYIN_OUTPUT_FORMAT);
        if (!fromLibrary.isEmpty()) {
            return fromLibrary;
        }
        if (c >= 'A' && c <= 'Z') {
            return String.valueOf((char) (c + 32));
        }
        if (c >= 'a' && c <= 'z') {
            return String.valueOf(c);
        }
        return "";
    }

    /**
     * The syllable with its tone number ({@code huo3}), used only as extra evidence when a repair has no
     * character in common with what was heard.
     */
    public static String toPinyinWithToneNumber(char c) {
        return lookupFromLibrary(c, PINYIN_TONE_FORMAT);
    }

    private static HanyuPinyinOutputFormat createOutputFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }

    private static HanyuPinyinOutputFormat createToneFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITH_TONE_NUMBER);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }

    private static String lookupFromLibrary(char c, HanyuPinyinOutputFormat format) {
        try {
            String[] candidates = PinyinHelper.toHanyuPinyinStringArray(c, format);
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