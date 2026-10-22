package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 委派注册表：<b>只做 5 秒复用窗口，不做键级去重</b>（设计 §5.4，v2/D2 定案）。
 *
 * <p>{@code replayKey = sha256(normalize(query))}，窗口内出现<b>逐字节相同</b>的 query 就复用既有 ticket，
 * 不新建 Run（A7）。它防的只有一件事——模型同一轮连发两次 {@code delegate}，或相邻两轮被重复触发；它
 * <b>不是</b>去重机制，也<b>不构成任何跨轮记忆</b>（§5.4 纪律 1–3）。因此本类：</p>
 * <ul>
 *   <li>不落盘、不进记忆、不进事件线（纯内存态，随会话丢弃）；</li>
 *   <li>不复用记忆侧的 {@code idempotencyKey}（A9）；</li>
 *   <li>窗口外一律新建 Run，不与"结论是否还在上下文里"发生关系。</li>
 * </ul>
 *
 * <p>规范化与 03 文档 D6 同源：NFC → 去全部空白与全半角差异 → 去句末标点 → 英文小写化。刻意<b>不做</b>
 * 同义改写、向量相似与拼音归一——那会把两个不同的问题合成一个。</p>
 */
public final class AXDelegateRegistry {

    /** 5s 复用窗口（§5.4 / A.2：可配置项 {@code replayWindowMs}，此处为默认值）。 */
    public static final long DEFAULT_REPLAY_WINDOW_MILLIS = 5_000L;

    /**
     * 每会话保留的 ticket 上限。
     *
     * <p>注册表纯内存态且随会话丢弃，但长会话里终态 ticket 会一直累积；超过上限时<b>只淘汰最旧的终态
     * ticket</b>，绝不淘汰未终态的那一个（淘汰活 ticket 会让投递丢失，A4 破）。16 远大于 5s 窗口内可能
     * 产生的数量，因此不影响复用判定。</p>
     */
    public static final int MAX_RETAINED_TICKETS_PER_SESSION = 16;

    private static final String TASK_ID_PREFIX = "ax.delegate.";
    private static final String TASK_ID_SEPARATOR = "-";
    private static final int TASK_ID_KEY_CHARS = 10;

    /** 句末标点：规范化时从尾部剥掉（§5.4 的 normalize）。 */
    private static final String TRAILING_PUNCTUATION = "。！？；.!?;，,、";

    private final long replayWindowMillis;
    private final Map<String, List<AXDelegateTicket>> ticketsBySession = new LinkedHashMap<>();
    private long sequence;

    public AXDelegateRegistry() {
        this(DEFAULT_REPLAY_WINDOW_MILLIS);
    }

    public AXDelegateRegistry(long replayWindowMillis) {
        if (replayWindowMillis <= 0L) {
            throw new IllegalArgumentException("replayWindowMillis must be positive");
        }
        this.replayWindowMillis = replayWindowMillis;
    }

    public long replayWindowMillis() {
        return replayWindowMillis;
    }

    /** {@link #open} 的结果：新建还是复用，调用方据此决定要不要真的起 Run。 */
    public record Reservation(AXDelegateTicket ticket, boolean created) {
        public Reservation {
            Objects.requireNonNull(ticket, "ticket");
        }
    }

    /**
     * §5.4 的 {@code onDelegate}：窗口内命中同 {@code replayKey} 就复用，否则新建。
     *
     * <p>同步块保证"查窗口 + 建 ticket"是原子的：两个并发委派不可能同时看不到对方而各建一个 Run。</p>
     */
    public synchronized Reservation open(AXScope scope, String sessionId, String query, long nowMillis) {
        String effectiveSession = sessionId == null ? "" : sessionId;
        String replayKey = replayKey(query);
        List<AXDelegateTicket> sessionTickets = ticketsBySession.computeIfAbsent(effectiveSession, key -> new ArrayList<>());
        for (int index = sessionTickets.size() - 1; index >= 0; index--) {
            AXDelegateTicket candidate = sessionTickets.get(index);
            if (!candidate.replayKey().equals(replayKey)) {
                continue;
            }
            if (nowMillis - candidate.createdAtMillis() > replayWindowMillis) {
                continue;
            }
            return new Reservation(candidate, false);
        }
        sequence++;
        AXDelegateTicket ticket = new AXDelegateTicket(
                TASK_ID_PREFIX + keyPrefix(replayKey) + TASK_ID_SEPARATOR + sequence,
                replayKey,
                effectiveSession,
                query == null ? "" : query.trim(),
                scope,
                nowMillis
        );
        sessionTickets.add(ticket);
        prune(sessionTickets);
        return new Reservation(ticket, true);
    }

    /** 按 taskId 查 ticket（投递与测试用）。 */
    public synchronized AXDelegateTicket find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        for (List<AXDelegateTicket> sessionTickets : ticketsBySession.values()) {
            for (AXDelegateTicket ticket : sessionTickets) {
                if (ticket.taskId().equals(taskId)) {
                    return ticket;
                }
            }
        }
        return null;
    }

    /** 该会话当前保留的 ticket 数（观测与测试用）。 */
    public synchronized int retainedCount(String sessionId) {
        List<AXDelegateTicket> sessionTickets = ticketsBySession.get(sessionId == null ? "" : sessionId);
        return sessionTickets == null ? 0 : sessionTickets.size();
    }

    /** 会话结束丢弃（§5.3 丢弃语义 / §6.2 会话结束丢弃队列）。 */
    public synchronized void clearSession(String sessionId) {
        ticketsBySession.remove(sessionId == null ? "" : sessionId);
    }

    /** {@code replayKey = sha256(normalize(query))}（§5.4）。空白 query 也返回一个稳定键，便于复用判定。 */
    public static String replayKey(String query) {
        return sha256Hex(normalize(query));
    }

    /** §5.4 的 {@code normalize}：NFC → 去空白与全半角差异 → 去句末标点 → 英文小写化。 */
    public static String normalize(String query) {
        String text = query == null ? "" : Normalizer.normalize(query, Normalizer.Form.NFC);
        StringBuilder folded = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\u3000') {
                continue;
            }
            if (current >= '\uFF01' && current <= '\uFF5E') {
                current = (char) (current - 0xFEE0);
            }
            if (Character.isWhitespace(current)) {
                continue;
            }
            folded.append(current);
        }
        int end = folded.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(folded.charAt(end - 1)) >= 0) {
            end--;
        }
        return folded.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static void prune(List<AXDelegateTicket> sessionTickets) {
        while (sessionTickets.size() > MAX_RETAINED_TICKETS_PER_SESSION) {
            int victim = -1;
            for (int index = 0; index < sessionTickets.size(); index++) {
                if (sessionTickets.get(index).isTerminal()) {
                    victim = index;
                    break;
                }
            }
            if (victim < 0) {
                return;
            }
            sessionTickets.remove(victim);
        }
    }

    private static String keyPrefix(String replayKey) {
        return replayKey.length() <= TASK_ID_KEY_CHARS ? replayKey : replayKey.substring(0, TASK_ID_KEY_CHARS);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                hex.append(Character.forDigit((current >> 4) & 0xF, 16));
                hex.append(Character.forDigit(current & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for the delegate replay key", unavailable);
        }
    }
}
