package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The wire format of the Edge read-aloud endpoint, as pure functions.
 *
 * <p>Everything here is text in, text out: the frames are built and parsed without a socket, so the protocol
 * can be tested against the real shape without a network and the transport stays replaceable.</p>
 */
public final class EdgeTtsProtocol {

    /**
     * The token Edge's own client sends. It identifies "the read-aloud consumer", not a user account — there
     * is no billing or login behind it.
     */
    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    /** The audio format the endpoint actually serves; PCM variants are rejected by the service. */
    public static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    /** Sample rate of the format above. */
    public static final int SAMPLE_RATE = 24_000;

    private static final String HEADER_SEPARATOR = "\r\n\r\n";

    /** One text frame: its {@code Path} header plus the body that follows the blank line. */
    public record Frame(String path, String body) {
    }

    private EdgeTtsProtocol() {
    }

    /**
     * Builds the websocket URL for one synthesis attempt.
     *
     * @param endpoint        {@code ws://} or {@code wss://} base, already resolved from profile/provider
     * @param token           the v2 {@code Sec-MS-GEC} value
     * @param version         the {@code Sec-MS-GEC-Version} value the token was derived for
     * @param connectionId    a fresh id per connection
     */
    public static String connectionUrl(String endpoint, String token, String version, String connectionId) {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        char separator = base.indexOf('?') >= 0 ? '&' : '?';
        return base + separator
                + "TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + token
                + "&Sec-MS-GEC-Version=" + version;
    }

    /** The {@code speech.config} frame that selects the output format. */
    public static String configFrame() {
        return "X-Timestamp:" + timestamp() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}\r\n";
    }

    /** The {@code ssml} frame that carries the text to speak. */
    public static String ssmlFrame(String requestId, String voiceId, String text) {
        String voice = voiceId == null || voiceId.isBlank() ? "" : voiceId.trim();
        if (voice.isEmpty()) {
            throw new IllegalArgumentException("voiceId must not be blank");
        }
        String speech = escapeXml(text == null ? "" : text);
        return "X-RequestId:" + requestId + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + timestamp() + "\r\n"
                + "Path:ssml\r\n\r\n"
                + "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + escapeXml(voiceName(voice)) + "'>"
                + "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>" + speech + "</prosody>"
                + "</voice></speak>";
    }

    /**
     * Expands the catalog's short voice id into the name the service expects.
     *
     * <p>The service does not accept {@code zh-CN-XiaoxiaoNeural}; it accepts the long form
     * {@code Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)}. Sending the short id makes
     * the socket close with a protocol error, which is why the expansion lives here rather than in the
     * catalog data.</p>
     */
    static String voiceName(String shortId) {
        String[] parts = shortId.split("-", 3);
        if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return shortId;
        }
        return "Microsoft Server Speech Text to Speech Voice (" + parts[0] + "-" + parts[1] + ", " + parts[2] + ")";
    }

    /**
     * Splits a text frame into its {@code Path} header and body.
     *
     * @return empty when the frame carries no header block at all, which the turn-boundary handling treats
     *         as "nothing to act on" rather than as a protocol error
     */
    public static Optional<Frame> parseTextFrame(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int split = raw.indexOf(HEADER_SEPARATOR);
        if (split < 0) {
            return Optional.empty();
        }
        String headers = raw.substring(0, split);
        String body = raw.substring(split + HEADER_SEPARATOR.length());
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Path")) {
                return Optional.of(new Frame(line.substring(colon + 1).trim(), body));
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the audio payload of a binary frame.
     *
     * <p>A binary frame starts with a two-byte big-endian header length, then that many bytes of text headers,
     * then the audio itself. Returning the payload as a slice keeps the caller from re-copying it per chunk.</p>
     */
    public static byte[] audioPayload(byte[] frame) {
        if (frame == null || frame.length < 2) {
            return new byte[0];
        }
        int headerLength = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        int audioStart = 2 + headerLength;
        if (headerLength <= 0 || audioStart >= frame.length) {
            return new byte[0];
        }
        byte[] audio = new byte[frame.length - audioStart];
        System.arraycopy(frame, audioStart, audio, 0, audio.length);
        return audio;
    }

    /** The timestamp format the service echoes back: {@code Date.toString()} in UTC, as the browser sends it. */
    static String timestamp() {
        return java.time.format.DateTimeFormatter
                .ofPattern("EEE MMM d yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)Z'", java.util.Locale.US)
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
    }

    /**
     * The User-Agent that matches the credential's version.
     *
     * <p>The endpoint refuses a handshake whose {@code Edg/} major disagrees with
     * {@code Sec-MS-GEC-Version}: measured, {@code Edg/130} with version {@code 2-143.0.3650.75} answers 403
     * while {@code Edg/143} answers 101. Deriving it here keeps the two from drifting apart.</p>
     */
    public static String userAgent(String version) {
        String value = version == null ? "" : version.trim();
        String chromium = value.startsWith("2-") ? value.substring(2) : value;
        int dot = chromium.indexOf('.');
        if (dot > 0) {
            chromium = chromium.substring(0, dot);
        }
        if (chromium.isEmpty() || !chromium.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("cloud credential version has no Chromium major: " + version);
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/" + chromium + ".0.0.0 Safari/537.36 Edg/" + chromium + ".0.0.0";
    }

    static String escapeXml(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }


}