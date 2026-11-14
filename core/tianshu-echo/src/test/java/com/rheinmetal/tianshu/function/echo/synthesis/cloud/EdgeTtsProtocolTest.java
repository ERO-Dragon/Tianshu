package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Edge wire format, exercised without a socket. */
class EdgeTtsProtocolTest {

    @Test
    void connectionUrlCarriesTheTokenAndConnection() {
        String url = EdgeTtsProtocol.connectionUrl(
                "wss://example.invalid/edge/v1", "ABC-123", "2-143.0.3650.75", "conn-1");

        assertTrue(url.startsWith("wss://example.invalid/edge/v1?"), url);
        assertTrue(url.contains("TrustedClientToken=" + EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN), url);
        assertTrue(url.contains("ConnectionId=conn-1"), url);
        assertTrue(url.contains("Sec-MS-GEC=ABC-123"), url);
        assertTrue(url.contains("Sec-MS-GEC-Version=2-143.0.3650.75"), url);
    }

    @Test
    void connectionUrlAppendsToAnExistingQuery() {
        String url = EdgeTtsProtocol.connectionUrl(
                "wss://example.invalid/edge/v1?proxy=1", "t", "v", "c");

        assertTrue(url.contains("?proxy=1&TrustedClientToken="), url);
        assertEquals(1, url.chars().filter(character -> character == '?').count(), url);
    }

    @Test
    void configFrameSelectsTheFormatTheServiceActuallyServes() {
        String frame = EdgeTtsProtocol.configFrame();

        assertTrue(frame.contains("Path:speech.config"), frame);
        assertTrue(frame.contains("\"outputFormat\":\"" + EdgeTtsProtocol.OUTPUT_FORMAT + "\""), frame);
        assertTrue(frame.contains("Content-Type:application/json"), frame);
    }

    @Test
    void ssmlFrameEscapesTextSoMarkupCannotBreakTheRequest() {
        String frame = EdgeTtsProtocol.ssmlFrame("req-1", "zh-CN-XiaoxiaoNeural", "a & b < c > d \"e\"");

        assertTrue(frame.contains("Path:ssml"), frame);
        assertTrue(frame.contains("X-RequestId:req-1"), frame);
        assertTrue(frame.contains("a &amp; b &lt; c &gt; d &quot;e&quot;"), frame);
        assertTrue(frame.contains("Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)"), frame);
    }

    @Test
    void ssmlFrameRefusesABlankVoice() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTtsProtocol.ssmlFrame("req-1", "   ", "hello"));
    }

    @Test
    void textFramePathAndBodyAreParsed() {
        String raw = "X-RequestId:r\r\nPath:turn.end\r\nContent-Type:application/json\r\n\r\n{\"ok\":true}";

        EdgeTtsProtocol.Frame frame = EdgeTtsProtocol.parseTextFrame(raw).orElseThrow();

        assertEquals("turn.end", frame.path());
        assertEquals("{\"ok\":true}", frame.body());
    }

    @Test
    void aFrameWithoutAHeaderBlockIsIgnoredRatherThanFailed() {
        assertTrue(EdgeTtsProtocol.parseTextFrame("no headers here").isEmpty());
        assertTrue(EdgeTtsProtocol.parseTextFrame(null).isEmpty());
    }

    @Test
    void binaryAudioPayloadSkipsTheHeaderBlock() {
        byte[] header = "Path:audio\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] audio = {1, 2, 3, 4};
        byte[] frame = new byte[2 + header.length + audio.length];
        frame[0] = (byte) (header.length >> 8);
        frame[1] = (byte) header.length;
        System.arraycopy(header, 0, frame, 2, header.length);
        System.arraycopy(audio, 0, frame, 2 + header.length, audio.length);

        assertArrayEquals(audio, EdgeTtsProtocol.audioPayload(frame));
    }

    @Test
    void aBinaryFrameWithoutAudioYieldsNothing() {
        assertArrayEquals(new byte[0], EdgeTtsProtocol.audioPayload(new byte[]{0, 0}));
        assertArrayEquals(new byte[0], EdgeTtsProtocol.audioPayload(null));
        assertArrayEquals(new byte[0], EdgeTtsProtocol.audioPayload(new byte[0]));
    }

    @Test
    void escapedTextRoundTripsThroughTheFrame() {
        String frame = EdgeTtsProtocol.ssmlFrame("r", "v", "<&>");
        assertTrue(frame.endsWith("</voice></speak>"), frame);
        assertTrue(frame.contains("&lt;&amp;&gt;"), frame);
    }
}