package com.rheinmetal.tianshu.function.auxilium.failure;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXFailureCauseTest {
    @Test
    void providerSideTroubleIsRetryable() {
        assertEquals(AXFailureCause.RATE_LIMITED, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=429 rate limit"));
        assertEquals(AXFailureCause.PROVIDER_ERROR, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=503 Service Unavailable"));
        assertEquals(AXFailureCause.TIMEOUT, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_STREAM_TIMEOUT timeoutSeconds=120"));
        assertEquals(AXFailureCause.NETWORK, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_TRANSPORT_FAILED java.net.UnknownHostException"));
        assertTrue(AXFailureCause.RATE_LIMITED.retryable());
        assertTrue(AXFailureCause.PROVIDER_ERROR.retryable());
        assertTrue(AXFailureCause.TIMEOUT.retryable());
        assertTrue(AXFailureCause.NETWORK.retryable());
        assertTrue(AXFailureCause.UNKNOWN.retryable(), "认不出来的失败缺省按可重试处理");
    }

    @Test
    void requestsThatCannotSucceedAreNotRetryable() {
        assertEquals(AXFailureCause.PARAMETER, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=401 invalid api key"));
        assertEquals(AXFailureCause.PARAMETER, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=400 bad request"));
        assertEquals(AXFailureCause.PARAMETER, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=403 forbidden"));
        assertEquals(AXFailureCause.CONTEXT_OVERFLOW, cause("LLM_INFERENCE_FAILED", "context_length_exceeded"));
        assertEquals(AXFailureCause.CONTENT_REFUSED, cause("LLM_INFERENCE_FAILED", "content_filter"));
        assertEquals(AXFailureCause.CANCELLED, cause("llm.cancel", "cancelled.OWNER_FAILED"));
        assertFalse(AXFailureCause.PARAMETER.retryable());
        assertFalse(AXFailureCause.CONTEXT_OVERFLOW.retryable());
        assertFalse(AXFailureCause.CONTENT_REFUSED.retryable());
        assertFalse(AXFailureCause.CANCELLED.retryable(), "被取消不是失败，重试只会再发一次没人要的话");
    }

    /** "Connection refused" 里的 refused 不是内容拒绝：它是必须重试的传输失败。 */
    @Test
    void aRefusedConnectionIsNotAContentRefusal() {
        assertEquals(
                AXFailureCause.NETWORK,
                cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_TRANSPORT_FAILED java.net.ConnectException: Connection refused")
        );
    }

    @Test
    void everyCauseNamesItsOwnSentence() {
        assertEquals("tianshu.runtime.module.ax.failed.rate_limited", AXFailureCause.RATE_LIMITED.messageKey());
        assertEquals(AXFailureCause.STATUS_KEY_UNKNOWN, AXFailureCause.UNKNOWN.messageKey());
        assertNotEquals(AXFailureCause.PARAMETER.messageKey(), AXFailureCause.PROVIDER_ERROR.messageKey());
        for (AXFailureCause value : AXFailureCause.values()) {
            assertFalse(value.messageKey().isBlank(), value + " 没有语言键");
            assertFalse(value.code().isBlank(), value + " 没有稳定标识");
        }
    }

    @Test
    void aStoredCauseReadsBackAndAnUnknownOneDegrades() {
        assertEquals(AXFailureCause.CONTEXT_OVERFLOW, AXFailureCause.fromCode("context_overflow"));
        assertEquals(AXFailureCause.UNKNOWN, AXFailureCause.fromCode("something_new"));
        assertEquals(AXFailureCause.UNKNOWN, AXFailureCause.fromCode(null));
    }

    @Test
    void classificationReadsTheBodyNotOnlyTheCode() {
        LLMPromptResultPayload failed = LLMPromptResultPayload.failed("request", "LLM_INFERENCE_FAILED", "status=429");
        assertEquals(AXFailureCause.RATE_LIMITED, AXFailureCause.classify(failed.errorCode(), failed.errorMessage()));
        // 同一个错误码，正文不同，结论必须不同：这就是为什么不能按码分类。
        LLMPromptResultPayload overflow = LLMPromptResultPayload.failed("request", "LLM_INFERENCE_FAILED", "context_length_exceeded");
        assertEquals(AXFailureCause.CONTEXT_OVERFLOW, AXFailureCause.classify(overflow.errorCode(), overflow.errorMessage()));
    }

    private static AXFailureCause cause(String code, String message) {
        return AXFailureCause.classify(code, message);
    }
}
