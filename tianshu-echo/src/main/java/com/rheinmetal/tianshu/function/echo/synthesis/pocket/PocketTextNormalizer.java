package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * Chinese text normalization for Pocket TTS.
 *
 * <p>This is the Java port of the model's {@code tn.py}: serial/phone numbers are read digit by
 * digit, dates and clock times use their spoken units, percentages and general numbers become
 * Chinese numerals, and half-width punctuation is widened to the tokens the Chinese vocabulary was
 * trained on. The Chinese lexemes and regexes live in a resource so this class contains no
 * localized player text. English terms and decimal points inside English abbreviations are left
 * untouched.</p>
 */
final class PocketTextNormalizer {
    private static final String RESOURCE =
            "/com/rheinmetal/tianshu/function/echo/synthesis/pocket/zh-normalization.json";
    private static final Rules RULES = loadRules();
    private static final Pattern SERIAL = Pattern.compile(RULES.serialPattern());
    private static final Pattern FLIGHT = Pattern.compile(RULES.flightPattern());
    private static final Pattern PHONE = Pattern.compile(RULES.phonePattern());
    private static final Pattern FULL_DATE = Pattern.compile(RULES.fullDatePattern());
    private static final Pattern ISO_DATE = Pattern.compile(RULES.isoDatePattern());
    private static final Pattern MONTH_DAY = Pattern.compile(RULES.monthDayPattern());
    private static final Pattern YEAR = Pattern.compile(RULES.yearPattern());
    private static final Pattern TIME = Pattern.compile(RULES.timePattern());
    private static final Pattern PERCENT = Pattern.compile(RULES.percentPattern());
    private static final Pattern NUMBER = Pattern.compile(RULES.numberPattern());

    private PocketTextNormalizer() {
    }

    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text.trim().replace('\n', ' ').replace('\r', ' ').replaceAll(" {2,}", " ");

        out = replace(SERIAL, out, match ->
                match.group(1) + match.group(2) + match.group(3) + digitwise(match.group(4)));
        out = replace(FLIGHT, out, match -> match.group(1) + digitwise(match.group(2)));
        out = replace(PHONE, out, match ->
                match.group(1) + match.group(2) + digitwise(match.group(3)));
        out = replace(FULL_DATE, out, match ->
                digitwise(match.group(1)) + RULES.yearUnit()
                        + numberToChinese(match.group(2)) + RULES.monthUnit()
                        + numberToChinese(match.group(3)) + match.group(4));
        out = replace(ISO_DATE, out, match ->
                digitwise(match.group(1)) + RULES.yearUnit()
                        + numberToChinese(match.group(2)) + RULES.monthUnit()
                        + numberToChinese(match.group(3)) + RULES.dayUnit());
        out = replace(MONTH_DAY, out, match ->
                numberToChinese(match.group(1)) + RULES.monthUnit()
                        + numberToChinese(match.group(2)) + match.group(3));
        out = replace(YEAR, out, match -> digitwise(match.group(1)) + RULES.yearUnit());
        out = replace(TIME, out, match -> {
            String hour = numberToChinese(match.group(1)) + RULES.hourUnit();
            if (match.group(2) == null) {
                return hour;
            }
            return hour + numberToChinese(match.group(2)) + RULES.minuteUnit();
        });
        out = replace(PERCENT, out, match -> RULES.percentPrefix() + numberToChinese(match.group(1)));
        out = replace(NUMBER, out, match -> numberToChinese(match.group(1)));
        out = normalizePunctuationWidth(out);
        if (!out.isEmpty() && RULES.terminalPunctuation().indexOf(out.charAt(out.length() - 1)) < 0) {
            out += RULES.terminalPunctuation().charAt(0);
        }
        return out;
    }

    private static String replace(Pattern pattern, String text, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String digitwise(String digits) {
        StringBuilder out = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            out.append(c >= '0' && c <= '9' ? RULES.digits().charAt(c - '0') : c);
        }
        return out.toString();
    }

    private static String numberToChinese(String literal) {
        if (literal == null || literal.isEmpty()) {
            return "";
        }
        boolean negative = literal.startsWith("-");
        String body = negative ? literal.substring(1) : literal;
        int dot = body.indexOf('.');
        String integer = dot < 0 ? body : body.substring(0, dot);
        String fraction = dot < 0 ? "" : body.substring(dot + 1);
        String spoken = integerToChinese(integer);
        if (!fraction.isEmpty()) {
            spoken += RULES.point() + digitwise(fraction);
        }
        return negative ? RULES.negative() + spoken : spoken;
    }

    private static String integerToChinese(String digits) {
        String trimmed = digits.replaceFirst("^0+(?!$)", "");
        if (trimmed.isEmpty()) {
            return RULES.zero();
        }
        if (trimmed.length() > 12) {
            return digitwise(trimmed);
        }
        StringBuilder out = new StringBuilder();
        boolean pendingZero = false;
        for (int i = 0; i < trimmed.length(); i++) {
            int digit = trimmed.charAt(i) - '0';
            int position = trimmed.length() - 1 - i;
            if (digit == 0) {
                pendingZero = true;
                continue;
            }
            if (pendingZero && out.length() > 0) {
                out.append(RULES.zero());
            }
            out.append(RULES.digits().charAt(digit));
            out.append(unit(position));
            pendingZero = false;
        }
        String result = out.toString();
        if (trimmed.length() == 2 && trimmed.charAt(0) == '1'
                && result.startsWith(RULES.digits().charAt(1) + RULES.ten())) {
            result = result.substring(1);
        }
        return result;
    }

    private static String unit(int position) {
        String section = switch (position % 4) {
            case 1 -> RULES.ten();
            case 2 -> RULES.hundred();
            case 3 -> RULES.thousand();
            default -> "";
        };
        if (position >= 8) {
            return section + RULES.hundredMillion();
        }
        if (position >= 4) {
            return section + RULES.tenThousand();
        }
        return section;
    }

    private static String normalizePunctuationWidth(String text) {
        String from = RULES.punctuationFrom();
        String to = RULES.punctuationTo();
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                boolean prevDigit = i > 0 && Character.isDigit(text.charAt(i - 1));
                boolean nextDigit = i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
                boolean prevLetter = i > 0 && isAsciiLetter(text.charAt(i - 1));
                boolean nextLetter = i + 1 < text.length() && isAsciiLetter(text.charAt(i + 1));
                if ((prevDigit && nextDigit) || (prevLetter && nextLetter)) {
                    out.append(c);
                    continue;
                }
            }
            int index = from.indexOf(c);
            out.append(index >= 0 ? to.charAt(index) : c);
        }
        return out.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static Rules loadRules() {
        try (InputStream stream = PocketTextNormalizer.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Pocket normalization resource: " + RESOURCE);
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(reader, Rules.class);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to load Pocket normalization resource", failure);
        }
    }

    private record Rules(
            String digits,
            String ten,
            String hundred,
            String thousand,
            String tenThousand,
            String hundredMillion,
            String zero,
            String point,
            String negative,
            String percentPrefix,
            String yearUnit,
            String monthUnit,
            String dayUnit,
            String hourUnit,
            String minuteUnit,
            String serialPattern,
            String flightPattern,
            String phonePattern,
            String fullDatePattern,
            String isoDatePattern,
            String monthDayPattern,
            String yearPattern,
            String timePattern,
            String percentPattern,
            String numberPattern,
            String punctuationFrom,
            String punctuationTo,
            String terminalPunctuation
    ) {
    }
}
