package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chinese text normalization for Pocket TTS.
 *
 * <p>This is the subset of the model's Python {@code tn.py} that Java can reproduce without a
 * wetext port: serial numbers are read digit by digit, half-width punctuation is widened to the
 * full-width tokens the Chinese vocabulary was trained on, and a terminal punctuation mark is
 * appended. The Chinese tables and regexes live in a resource so this class contains no localized
 * player text. Date, money and telephone normalization still belong to the text front-end; they are
 * deliberately left to the model rather than guessed here.</p>
 */
final class PocketTextNormalizer {
    private static final String RESOURCE = "/com/rheinmetal/tianshu/function/echo/synthesis/pocket/zh-normalization.json";
    private static final Rules RULES = loadRules();
    private static final Pattern SERIAL = Pattern.compile(RULES.serialPattern());
    private static final Pattern FLIGHT = Pattern.compile(RULES.flightPattern());

    private PocketTextNormalizer() {
    }

    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text.trim().replace('\n', ' ').replace('\r', ' ').replaceAll(" {2,}", " ");

        Matcher serial = SERIAL.matcher(out);
        StringBuilder buffer = new StringBuilder();
        while (serial.find()) {
            serial.appendReplacement(buffer, Matcher.quoteReplacement(
                    serial.group(1) + serial.group(2) + serial.group(3) + digitwise(serial.group(4))));
        }
        serial.appendTail(buffer);
        out = buffer.toString();

        Matcher flight = FLIGHT.matcher(out);
        StringBuilder flights = new StringBuilder();
        while (flight.find()) {
            flight.appendReplacement(flights, Matcher.quoteReplacement(flight.group(1) + digitwise(flight.group(2))));
        }
        flight.appendTail(flights);
        out = flights.toString();

        out = normalizePunctuationWidth(out);
        if (!out.isEmpty() && RULES.terminalPunctuation().indexOf(out.charAt(out.length() - 1)) < 0) {
            out += RULES.terminalPunctuation().charAt(0);
        }
        return out;
    }

    private static String digitwise(String digits) {
        StringBuilder out = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            out.append(c >= '0' && c <= '9' ? RULES.digits().charAt(c - '0') : c);
        }
        return out.toString();
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
            String serialPattern,
            String flightPattern,
            String punctuationFrom,
            String punctuationTo,
            String terminalPunctuation
    ) {
    }
}
