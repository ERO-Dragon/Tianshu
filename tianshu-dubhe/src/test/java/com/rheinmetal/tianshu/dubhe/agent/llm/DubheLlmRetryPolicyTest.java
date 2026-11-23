package com.rheinmetal.tianshu.dubhe.agent.llm;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheLlmRetryPolicyTest {
    @Test
    void transientProviderFailuresAreRetried() {
        assertTrue(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=429 rate_limit exceeded")));
        assertTrue(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=500 internal error")));
        assertTrue(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=503 Service Unavailable")));
        assertTrue(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_STREAM_TIMEOUT timeoutSeconds=120")));
        assertTrue(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_TRANSPORT_FAILED java.net.UnknownHostException")));
    }

    @Test
    void aFailureWithNothingToClassifyIsRetried() {
        assertTrue(DubheLlmRetryPolicy.isRetryable(failure("LLM request failed")));
        assertTrue(DubheLlmRetryPolicy.isRetryable(LLMPromptResultPayload.failed("request", "", "")));
    }

    @Test
    void parameterErrorsAreNeverRetried() {
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=400 bad request")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=401 invalid api key")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=403 forbidden")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("REMOTE_LLM_HTTP_STATUS status=422 unprocessable")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("invalid_request_error: messages must not be empty")));
    }

    @Test
    void contextOverflowIsNeverRetried() {
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("context_length_exceeded: maximum context reached")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("the prompt is too long for this model")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("max_tokens exceeds the token limit")));
    }

    @Test
    void aContentRefusalIsNeverRetried() {
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("content_filter: the reply was filtered")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("blocked by safety policy violation")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(failure("model returned a refusal")));
    }

    /** "Connection refused" 里的 refused 不是内容拒绝：它是必须重投的传输失败。 */
    @Test
    void aRefusedConnectionIsNotMistakenForAContentRefusal() {
        assertTrue(DubheLlmRetryPolicy.isRetryable(
                failure("REMOTE_LLM_TRANSPORT_FAILED java.net.ConnectException: Connection refused: connect")
        ));
    }

    @Test
    void onlyFailedResultsAreRetryable() {
        assertFalse(DubheLlmRetryPolicy.isRetryable(null));
        assertFalse(DubheLlmRetryPolicy.isRetryable(LLMPromptResultPayload.completed("request", "answer")));
        assertFalse(DubheLlmRetryPolicy.isRetryable(LLMPromptResultPayload.cancelled("request", "")));
    }

    @Test
    void theBackoffDoublesAndHasNoRungBeyondTheLastAttempt() {
        assertEquals(500L, DubheLlmRetryPolicy.delayMillis(1));
        assertEquals(1_000L, DubheLlmRetryPolicy.delayMillis(2));
        assertEquals(2_000L, DubheLlmRetryPolicy.delayMillis(3));
        assertEquals(4_000L, DubheLlmRetryPolicy.delayMillis(4));
        // 第 5 遍是终点，没有第 6 遍可排期；越界取最后一档而不是抛异常。
        assertEquals(4_000L, DubheLlmRetryPolicy.delayMillis(DubheLlmRetryPolicy.MAX_ATTEMPTS));
        assertEquals(4_000L, DubheLlmRetryPolicy.delayMillis(99));
    }

    private static LLMPromptResultPayload failure(String message) {
        return LLMPromptResultPayload.failed("request", "LLM_INFERENCE_FAILED", message);
    }
}
