package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

/**
 * AX 的 LLM 请求生命周期：投出一遍、跟住它、把终态结果交回发起方；失败时按 {@link AXLlmRetryPolicy}
 * 有限重投，直到拿到一个真正的答复或者用满投递次数。
 *
 * <p><b>重投带来的两种身份。</b>一次逻辑请求不再等于一只信封，所以这里把两者分开数：
 * <ul>
 *   <li><b>逻辑请求</b>（{@link PendingRequest#requestId()}，首发那一遍的 envelopeId）：调用方手里拿的是它，
 *       取消、打断、过期、按 lane 归类也都按它算，重投不改变它的值；</li>
 *   <li><b>一遍</b>（attempt 的 envelopeId）：真正发出去的那只信封，结果与前缀流按它回来，所以回投用
 *       {@code attempts} 索引。</li>
 * </ul>
 * 两张表指向同一个 {@link PendingRequest}，只经 {@link #track} 与 {@link #forget} 改动，任何一个 id 都能
 * 解析回同一个请求（{@link #resolve}）。
 *
 * <p><b>退避等待期间也一样能被取消。</b>两遍之间没有在途信封，但逻辑请求仍留在 {@code requests} 里：
 * 这半秒里发生的取消、打断、过期都还能命中它，被丢掉的是那一刻尚未发出的下一遍（{@link PendingRequest#beginAttempt}
 * 是最后一道门）。若不这么做，玩家打断之后半秒，一个已经没人要的请求还会自己飞出去。
 *
 * <p><b>重投只推迟终态，不改变终态。</b>发起方只会收到一次 {@code onResult}：要么最后一次失败，要么中间
 * 某一次成功；非重投失败与重投次数用尽都走同一条收尾路径，所以上层看到的失败含义不变。
 */
public final class AXLlmClient {
    /** 重投诊断码：一次失败换来的一次推迟。 */
    private static final String RETRY_DIAGNOSTIC = "ax.llm.retry";
    /** 上一遍失败、下一遍却连信封都没能建出来：这是 AX 自己的失败，不是平台的。 */
    private static final String RETRY_FAILED_CODE = "AX_LLM_RETRY_FAILED";
    private static final String UNKNOWN_REASON = "unknown";

    private final AXProtocolAdapter adapter;
    private final LongSupplier nowMillis;
    private final DiagnosticSink diagnostics;
    private final RetryScheduler scheduler;
    /** 在途的每一遍，按该遍的 envelopeId 索引：结果与前缀流按这个 id 回来。 */
    private final Map<String, PendingRequest> attempts = new ConcurrentHashMap<>();
    /** 逻辑请求，按首发 envelopeId 索引：调用方拿到的 id，取消与过期按它算。 */
    private final Map<String, PendingRequest> requests = new ConcurrentHashMap<>();

    public AXLlmClient(AXProtocolAdapter adapter) {
        this(adapter, DiagnosticSink.NOOP);
    }

    public AXLlmClient(AXProtocolAdapter adapter, DiagnosticSink diagnostics) {
        this(adapter, System::currentTimeMillis, diagnostics, AXLlmClient::scheduleOnCommonPool);
    }

    AXLlmClient(AXProtocolAdapter adapter, LongSupplier nowMillis) {
        this(adapter, nowMillis, DiagnosticSink.NOOP, AXLlmClient::scheduleOnCommonPool);
    }

    /**
     * @param scheduler 重投的排期方式；传 {@code null} 用公共池。测试传一个就地执行的实现，重投便同步发生，
     *                  断言不必等真实退避时间
     */
    public AXLlmClient(AXProtocolAdapter adapter, LongSupplier nowMillis, DiagnosticSink diagnostics, RetryScheduler scheduler) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
        this.scheduler = scheduler == null ? AXLlmClient::scheduleOnCommonPool : scheduler;
    }

    public TianshuEnvelope submit(TianshuEnvelope parent, LLMPromptRequestPayload payload, AXLlmRequestHandler handler) {
        Objects.requireNonNull(parent, "parent");
        return dispatch(adapter.buildLlmRequest(parent, payload), handler);
    }

    public TianshuEnvelope submitDetached(LLMPromptRequestPayload payload, AXLlmRequestHandler handler) {
        return dispatch(adapter.buildLlmRequest(payload), handler);
    }

    public boolean handleStreamChunk(String attemptEnvelopeId, LLMPromptStreamChunkPayload payload) {
        if (attemptEnvelopeId == null || attemptEnvelopeId.isBlank()) {
            return false;
        }
        PendingRequest request = attempts.get(attemptEnvelopeId);
        if (request == null) {
            return false;
        }
        if (request.expired(nowMillis.getAsLong())) {
            cancelAndForget(request, AXTurnCancellation.expired("AX LLM request expired"));
            return false;
        }
        if (request.cancelling()) {
            return true;
        }
        request.handler().onStreamChunk(payload);
        return true;
    }

    public boolean handleResult(String attemptEnvelopeId, LLMPromptResultPayload payload) {
        if (attemptEnvelopeId == null || attemptEnvelopeId.isBlank()) {
            return false;
        }
        PendingRequest request = attempts.remove(attemptEnvelopeId);
        if (request == null) {
            adapter.unregisterLlmResponses(attemptEnvelopeId);
            return false;
        }
        adapter.unregisterLlmResponses(attemptEnvelopeId);
        request.endAttempt();
        if (request.cancelling()) {
            forget(request);
            request.handler().onCancellationResult(payload);
            return true;
        }
        if (request.expired(nowMillis.getAsLong())) {
            forget(request);
            request.notifyCancelled(AXTurnCancellation.expired("AX LLM request expired"));
            return false;
        }
        if (request.mayRetry() && AXLlmRetryPolicy.isRetryable(payload)) {
            retryAfterDelay(request, payload);
            return true;
        }
        forget(request);
        request.handler().onResult(payload);
        return true;
    }

    public void cancelAll(AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM client cancelled")
                : cancellation;
        requests.values().forEach(request -> cancelAndForget(request, effective));
        requests.clear();
        attempts.clear();
    }

    public boolean cancelChatRequests(AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.playerInterrupted("AX chat request cancelled")
                : cancellation;
        AtomicBoolean cancelled = new AtomicBoolean(false);
        requests.values().forEach(request -> {
            if (!"SIMPLE".equals(request.lane())) {
                return;
            }
            cancelAndAwaitTerminalResult(request, effective);
            cancelled.set(true);
        });
        return cancelled.get();
    }

    public boolean cancelRequest(String requestEnvelopeId, AXTurnCancellation cancellation) {
        PendingRequest request = resolve(requestEnvelopeId);
        if (request == null) {
            return false;
        }
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        cancelAndForget(request, effective);
        return true;
    }

    /** Retains the handler until the provider returns its terminal cancellation result. */
    public boolean cancelRequestAwaitTerminal(String requestEnvelopeId, AXTurnCancellation cancellation) {
        PendingRequest request = resolve(requestEnvelopeId);
        if (request == null) {
            return false;
        }
        cancelAndAwaitTerminalResult(request, cancellation);
        return true;
    }

    public void clear() {
        cancelAll(AXTurnCancellation.moduleUnloaded("AX module stopped"));
    }

    public void sweepExpired() {
        long now = nowMillis.getAsLong();
        requests.values().forEach(request -> {
            if (request.expired(now)) {
                cancelAndForget(request, AXTurnCancellation.expired("AX LLM request expired"));
            }
        });
    }

    /**
     * 把一次失败变成一次推迟的投递。
     *
     * <p>投递点搬到了别的线程上，所以这里只做两件事：把遍数定下来（{@link PendingRequest#attemptsMade()} 已经在
     * 上一遍投出时加过），然后排期。发包前的最后一道门是 {@link #submitRetry} 与
     * {@link PendingRequest#beginAttempt}——那半秒里发生的取消、过期都由它们把这一遍挡掉。
     */
    private void retryAfterDelay(PendingRequest request, LLMPromptResultPayload payload) {
        int attemptsMade = request.attemptsMade();
        long delayMillis = AXLlmRetryPolicy.delayMillis(attemptsMade);
        publishRetry(request, attemptsMade + 1, delayMillis, payload);
        scheduler.schedule(delayMillis, () -> submitRetry(request));
    }

    private void submitRetry(PendingRequest request) {
        if (requests.get(request.requestId()) != request) {
            return;
        }
        if (request.expired(nowMillis.getAsLong())) {
            forget(request);
            request.notifyCancelled(AXTurnCancellation.expired("AX LLM request expired"));
            return;
        }
        LLMPromptRequestPayload payload = request.payload();
        if (payload == null) {
            forget(request);
            request.notifyCancelled(AXTurnCancellation.moduleUnloaded("AX LLM request cannot be retried"));
            return;
        }
        try {
            send(request, adapter.buildLlmRetry(request.traceId(), request.parentId(), payload));
        } catch (RuntimeException failure) {
            // 上一遍失败、下一遍连信封都没建出来：不把这一轮挂在这里等着，就地给出终态失败。
            forget(request);
            request.handler().onResult(LLMPromptResultPayload.failed(
                    request.requestId(),
                    RETRY_FAILED_CODE,
                    failure.getMessage()
            ));
        }
    }

    private TianshuEnvelope dispatch(TianshuEnvelope envelope, AXLlmRequestHandler handler) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(handler, "handler");
        PendingRequest request = new PendingRequest(
                envelope.envelopeId(),
                handler,
                envelope.header().expireAt(),
                payloadLane(envelope),
                requestPayload(envelope),
                envelope.header().traceId(),
                envelope.header().parentId()
        );
        send(request, envelope);
        sweepExpired();
        return envelope;
    }

    /** 投出去一遍：登记这一遍与它所属的逻辑请求，接上两条回投，然后交给协议层。 */
    private void send(PendingRequest request, TianshuEnvelope attempt) {
        if (!request.beginAttempt(attempt)) {
            forget(request);
            return;
        }
        track(request, attempt);
        adapter.registerLlmPromptStreamChunkResponse(attempt.envelopeId(), this::handleStreamChunkResponse);
        adapter.registerLlmPromptResultResponse(attempt.envelopeId(), this::handleResultResponse);
        adapter.submitLlmRequest(attempt);
    }

    private void track(PendingRequest request, TianshuEnvelope attempt) {
        requests.put(request.requestId(), request);
        attempts.put(attempt.envelopeId(), request);
    }

    private void forget(PendingRequest request) {
        requests.remove(request.requestId(), request);
        TianshuEnvelope latest = request.envelope();
        if (latest != null) {
            attempts.remove(latest.envelopeId(), request);
        }
    }

    /** 按任意一个 id 找回请求：调用方手里可能是逻辑 id，回投给的是某一遍的 id。 */
    private PendingRequest resolve(String envelopeId) {
        if (envelopeId == null || envelopeId.isBlank()) {
            return null;
        }
        PendingRequest request = requests.get(envelopeId);
        return request != null ? request : attempts.get(envelopeId);
    }

    private void handleStreamChunkResponse(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMPromptStreamChunkPayload payload)) {
            if (context != null) {
                context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX LLM stream payload is invalid", null);
            }
            return;
        }
        handleStreamChunk(envelope.parentId(), payload);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void handleResultResponse(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMPromptResultPayload payload)) {
            if (context != null) {
                context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX LLM result payload is invalid", null);
            }
            return;
        }
        handleResult(envelope.parentId(), payload);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void cancelAndAwaitTerminalResult(PendingRequest request, AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        if (!request.markCancelling()) {
            return;
        }
        TianshuEnvelope inFlight = request.inFlightEnvelope();
        if (inFlight == null) {
            // 正在等下一遍重投：没有在途信封，也就没有终态结果会回来了，就地结束。
            forget(request);
            request.notifyCancelled(effective);
            return;
        }
        try {
            request.notifyCancelled(effective);
        } finally {
            adapter.cancelLlmRequest(inFlight, cancellationReasonCode(effective), effective.message());
        }
    }

    private void cancelAndForget(PendingRequest request, AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        // 先钉住"已取消"再摘牌：退避等待里的那一次排期即便随后触发，也会被 beginAttempt 挡在门外。
        request.markCancelling();
        TianshuEnvelope inFlight = request.inFlightEnvelope();
        forget(request);
        if (inFlight != null) {
            adapter.unregisterLlmResponses(inFlight.envelopeId());
        }
        try {
            if (inFlight != null) {
                adapter.cancelLlmRequest(inFlight, cancellationReasonCode(effective), effective.message());
            }
        } finally {
            request.notifyCancelled(effective);
        }
    }

    private void publishRetry(PendingRequest request, int nextAttempt, long delayMillis, LLMPromptResultPayload payload) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("requestId", bounded(request.requestId(), 64));
        attributes.put("attempt", Integer.toString(nextAttempt));
        attributes.put("maxAttempts", Integer.toString(AXLlmRetryPolicy.MAX_ATTEMPTS));
        attributes.put("delayMs", Long.toString(delayMillis));
        attributes.put("lane", request.lane());
        attributes.put("reason", bounded(reasonOf(payload), 64));
        diagnostics.publish(DiagnosticEvent.now(
                AXModule.MODULE_ID,
                RETRY_DIAGNOSTIC,
                DiagnosticSeverity.DEBUG,
                DiagnosticPrivacy.REDACTED,
                attributes
        ));
    }

    private static String reasonOf(LLMPromptResultPayload payload) {
        if (payload == null) {
            return UNKNOWN_REASON;
        }
        if (payload.errorCode() != null) {
            return payload.errorCode();
        }
        return payload.errorMessage() == null ? UNKNOWN_REASON : payload.errorMessage();
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String cancellationReasonCode(AXTurnCancellation cancellation) {
        if (cancellation == null || cancellation.releaseReason() == null) {
            return "AX_LLM_REQUEST_CANCELLED";
        }
        return "AX_" + cancellation.releaseReason().name();
    }

    private String payloadLane(TianshuEnvelope envelope) {
        if (envelope != null && envelope.payload() instanceof LLMPromptRequestPayload payload) {
            return payload.lane();
        }
        return "SIMPLE";
    }

    private static LLMPromptRequestPayload requestPayload(TianshuEnvelope envelope) {
        return envelope != null && envelope.payload() instanceof LLMPromptRequestPayload payload ? payload : null;
    }

    /** 默认排期：公共池上的延迟执行器，没有线程可以泄漏，也没有生命周期要收。 */
    private static void scheduleOnCommonPool(long delayMillis, Runnable task) {
        CompletableFuture.delayedExecutor(Math.max(0L, delayMillis), TimeUnit.MILLISECONDS).execute(task);
    }

    /** 把一次重投推迟到以后；测试传一个就地执行的实现，重投便同步发生。 */
    @FunctionalInterface
    public interface RetryScheduler {
        void schedule(long delayMillis, Runnable task);
    }

    /**
     * 一次逻辑请求，横跨它的每一遍投递。
     *
     * <p>可变字段都加锁：结果在一遍回来的线程上处理，取消可能来自回合线程、tick 线程或任意调用方，
     * 而"遍数"与"是否在途"是两者都要读写的。
     */
    private static final class PendingRequest {
        private final String requestId;
        private final AXLlmRequestHandler handler;
        private final long expireAtMillis;
        private final String lane;
        private final LLMPromptRequestPayload payload;
        private final String traceId;
        private final String parentId;
        private final AtomicBoolean cancelling = new AtomicBoolean(false);
        private final AtomicBoolean cancellationNotified = new AtomicBoolean(false);
        private TianshuEnvelope envelope;
        private int attemptsMade;
        private boolean inFlight;

        private PendingRequest(
                String requestId,
                AXLlmRequestHandler handler,
                long expireAtMillis,
                String lane,
                LLMPromptRequestPayload payload,
                String traceId,
                String parentId
        ) {
            this.requestId = Objects.requireNonNull(requestId, "requestId");
            this.handler = Objects.requireNonNull(handler, "handler");
            this.expireAtMillis = expireAtMillis;
            this.lane = lane == null || lane.isBlank() ? "SIMPLE" : lane.trim().toUpperCase();
            this.payload = payload;
            this.traceId = traceId;
            this.parentId = parentId;
        }

        private String requestId() {
            return requestId;
        }

        private AXLlmRequestHandler handler() {
            return handler;
        }

        private String lane() {
            return lane;
        }

        private LLMPromptRequestPayload payload() {
            return payload;
        }

        /** 首发那一封的 traceId：重投的每一遍都沿用，一条请求始终在一条 trace 上。 */
        private String traceId() {
            return traceId;
        }

        /** 首发那一封的 parentId：重投的每一遍都沿用它，所以各遍在追踪图里是兄弟而不是一条链。 */
        private String parentId() {
            return parentId;
        }

        /** 当前这一遍的信封：撤单与回投都要它。 */
        private synchronized TianshuEnvelope envelope() {
            return envelope;
        }

        /** 在途的那只信封；正在等下一遍重投时为 {@code null}。 */
        private synchronized TianshuEnvelope inFlightEnvelope() {
            return inFlight ? envelope : null;
        }

        private synchronized int attemptsMade() {
            return attemptsMade;
        }

        private synchronized boolean mayRetry() {
            return payload != null && attemptsMade < AXLlmRetryPolicy.MAX_ATTEMPTS;
        }

        /** 记下这一遍；已经放弃或被取消就返回 {@code false}，这一遍由此不再发出。 */
        private synchronized boolean beginAttempt(TianshuEnvelope attemptEnvelope) {
            if (cancelling.get() || attemptEnvelope == null) {
                return false;
            }
            envelope = attemptEnvelope;
            attemptsMade++;
            inFlight = true;
            return true;
        }

        /** 这一遍已经交出结果：手上没有在途信封了，下一遍仍要沿用它的信封链。 */
        private synchronized void endAttempt() {
            inFlight = false;
        }

        private boolean cancelling() {
            return cancelling.get();
        }

        private boolean markCancelling() {
            return cancelling.compareAndSet(false, true);
        }

        private void notifyCancelled(AXTurnCancellation cancellation) {
            if (cancellationNotified.compareAndSet(false, true)) {
                handler.onCancelled(cancellation);
            }
        }

        private boolean expired(long nowMillis) {
            if ("COMPLEX".equals(lane)) {
                return false;
            }
            return expireAtMillis > 0L && expireAtMillis < nowMillis;
        }
    }
}
