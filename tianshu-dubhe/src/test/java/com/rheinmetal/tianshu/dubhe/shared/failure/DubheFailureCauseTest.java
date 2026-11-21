package com.rheinmetal.tianshu.dubhe.shared.failure;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheFailureCauseTest {
    @Test
    void providerSideTroubleIsRetryable() {
        assertEquals(DubheFailureCause.RATE_LIMITED, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=429 rate limit"));
        assertEquals(DubheFailureCause.PROVIDER_ERROR, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=503 Service Unavailable"));
        assertEquals(DubheFailureCause.TIMEOUT, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_STREAM_TIMEOUT timeoutSeconds=120"));
        assertEquals(DubheFailureCause.NETWORK, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_TRANSPORT_FAILED java.net.UnknownHostException"));
        assertTrue(DubheFailureCause.RATE_LIMITED.retryable());
        assertTrue(DubheFailureCause.PROVIDER_ERROR.retryable());
        assertTrue(DubheFailureCause.TIMEOUT.retryable());
        assertTrue(DubheFailureCause.NETWORK.retryable());
        assertTrue(DubheFailureCause.UNKNOWN.retryable(), "认不出来的失败缺省按可重试处理");
    }

    @Test
    void requestsThatCannotSucceedAreNotRetryable() {
        assertEquals(DubheFailureCause.PARAMETER, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=401 invalid api key"));
        assertEquals(DubheFailureCause.PARAMETER, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=400 bad request"));
        assertEquals(DubheFailureCause.PARAMETER, cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_HTTP_STATUS status=403 forbidden"));
        assertEquals(DubheFailureCause.CONTEXT_OVERFLOW, cause("LLM_INFERENCE_FAILED", "context_length_exceeded"));
        assertEquals(DubheFailureCause.CONTENT_REFUSED, cause("LLM_INFERENCE_FAILED", "content_filter"));
        assertEquals(DubheFailureCause.CANCELLED, cause("llm.cancel", "cancelled.OWNER_FAILED"));
        assertFalse(DubheFailureCause.PARAMETER.retryable());
        assertFalse(DubheFailureCause.CONTEXT_OVERFLOW.retryable());
        assertFalse(DubheFailureCause.CONTENT_REFUSED.retryable());
        assertFalse(DubheFailureCause.CANCELLED.retryable(), "被取消不是失败，重试只会再发一次没人要的话");
    }

    /** "Connection refused" 里的 refused 不是内容拒绝：它是必须重试的传输失败。 */
    @Test
    void aRefusedConnectionIsNotAContentRefusal() {
        assertEquals(
                DubheFailureCause.NETWORK,
                cause("LLM_INFERENCE_FAILED", "REMOTE_LLM_TRANSPORT_FAILED java.net.ConnectException: Connection refused")
        );
    }

    @Test
    void everyCauseNamesItsOwnSentence() {
        assertEquals("tianshu.runtime.module.dubhe.failed.rate_limited", DubheFailureCause.RATE_LIMITED.messageKey());
        assertEquals(DubheFailureCause.STATUS_KEY_UNKNOWN, DubheFailureCause.UNKNOWN.messageKey());
        assertNotEquals(DubheFailureCause.PARAMETER.messageKey(), DubheFailureCause.PROVIDER_ERROR.messageKey());
        for (DubheFailureCause value : DubheFailureCause.values()) {
            assertFalse(value.messageKey().isBlank(), value + " 没有语言键");
            assertFalse(value.code().isBlank(), value + " 没有稳定标识");
        }
    }

    @Test
    void aStoredCauseReadsBackAndAnUnknownOneDegrades() {
        assertEquals(DubheFailureCause.CONTEXT_OVERFLOW, DubheFailureCause.fromCode("context_overflow"));
        assertEquals(DubheFailureCause.UNKNOWN, DubheFailureCause.fromCode("something_new"));
        assertEquals(DubheFailureCause.UNKNOWN, DubheFailureCause.fromCode(null));
    }

    @Test
    void classificationReadsTheBodyNotOnlyTheCode() {
        LLMPromptResultPayload failed = LLMPromptResultPayload.failed("request", "LLM_INFERENCE_FAILED", "status=429");
        assertEquals(DubheFailureCause.RATE_LIMITED, DubheFailureCause.classify(failed.errorCode(), failed.errorMessage()));
        // 同一个错误码，正文不同，结论必须不同：这就是为什么不能按码分类。
        LLMPromptResultPayload overflow = LLMPromptResultPayload.failed("request", "LLM_INFERENCE_FAILED", "context_length_exceeded");
        assertEquals(DubheFailureCause.CONTEXT_OVERFLOW, DubheFailureCause.classify(overflow.errorCode(), overflow.errorMessage()));
    }

    private static DubheFailureCause cause(String code, String message) {
        return DubheFailureCause.classify(code, message);
    }
}
