package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXLlmRetryPolicyTest {
    @Test
    void transientProviderFailuresAreRetried() {
        assertTrue(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=429 rate_limit exceeded")));
        assertTrue(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=500 internal error")));
        assertTrue(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=503 Service Unavailable")));
        assertTrue(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_STREAM_TIMEOUT timeoutSeconds=120")));
        assertTrue(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_TRANSPORT_FAILED java.net.UnknownHostException")));
    }

    @Test
    void aFailureWithNothingToClassifyIsRetried() {
        assertTrue(AXLlmRetryPolicy.isRetryable(failure("LLM request failed")));
        assertTrue(AXLlmRetryPolicy.isRetryable(new LLMPromptResultPayload(
                "request", "FAILED", "", null, null, java.util.List.of(), null
        )));
    }

    @Test
    void parameterErrorsAreNeverRetried() {
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=400 bad request")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=401 invalid api key")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=403 forbidden")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=422 unprocessable")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("invalid_request_error: messages must not be empty")));
    }

    @Test
    void contextOverflowIsNeverRetried() {
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("context_length_exceeded: maximum context reached")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("the prompt is too long for this model")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("max_tokens exceeds the token limit")));
    }

    @Test
    void aContentRefusalIsNeverRetried() {
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("content_filter: the reply was filtered")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("blocked by safety policy violation")));
        assertFalse(AXLlmRetryPolicy.isRetryable(failure("model returned a refusal")));
    }

    /** "Connection refused" 里的 refused 不是内容拒绝：它是必须重投的传输失败。 */
    @Test
    void aRefusedConnectionIsNotMistakenForAContentRefusal() {
        assertTrue(AXLlmRetryPolicy.isRetryable(
                failure("REMOTE_LLM_TRANSPORT_FAILED java.net.ConnectException: Connection refused: connect")
        ));
    }

    @Test
    void onlyFailedResultsAreRetryable() {
        assertFalse(AXLlmRetryPolicy.isRetryable(null));
        assertFalse(AXLlmRetryPolicy.isRetryable(LLMPromptResultPayload.completed("request", "answer")));
        assertFalse(AXLlmRetryPolicy.isRetryable(LLMPromptResultPayload.cancelled("request", "")));
    }

    @Test
    void theBackoffDoublesAndHasNoRungBeyondTheLastAttempt() {
        assertEquals(500L, AXLlmRetryPolicy.delayMillis(1));
        assertEquals(1_000L, AXLlmRetryPolicy.delayMillis(2));
        assertEquals(2_000L, AXLlmRetryPolicy.delayMillis(3));
        assertEquals(4_000L, AXLlmRetryPolicy.delayMillis(4));
        // 第 5 遍是终点，没有第 6 遍可排期；越界取最后一档而不是抛异常。
        assertEquals(4_000L, AXLlmRetryPolicy.delayMillis(AXLlmRetryPolicy.MAX_ATTEMPTS));
        assertEquals(4_000L, AXLlmRetryPolicy.delayMillis(99));
    }

    private static LLMPromptResultPayload failure(String message) {
        return LLMPromptResultPayload.failed("request", "LLM_INFERENCE_FAILED", message);
    }
}
