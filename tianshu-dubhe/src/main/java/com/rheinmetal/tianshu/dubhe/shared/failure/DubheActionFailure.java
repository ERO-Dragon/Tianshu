package com.rheinmetal.tianshu.dubhe.shared.failure;

import java.util.Locale;

/**
 * 办事失败的原因，与 API 失败（{@link DubheFailureCause}）分开。
 *
 * <p>玩家听到的必须是游戏里的原因（"箱子锁着""够不着""没这个权限"），不是错误码；API 层的原因只进诊断。
 * 每类原因对应 {@code dubhe_reply_texts.json} 里的 {@code action.failure.<id>} 一句话，代码里不出现自然语言。</p>
 */
public enum DubheActionFailure {

    /** 被拒绝：权限不足、档位不够、对方不允许。 */
    NOT_ALLOWED("not_allowed"),
    /** 找不到能办这件事的对象。 */
    TARGET_UNKNOWN("target_unknown"),
    /** 对方的功能被关掉了，或没有安装。 */
    TARGET_OFF("target_off"),
    /** 对方正忙。 */
    TARGET_BUSY("target_busy"),
    /** 等太久。 */
    TIME_LIMIT("time_limit"),
    /** 请求太频繁。 */
    TOO_OFTEN("too_often"),
    /** 服务端上限拦下。 */
    SERVER_LIMIT("server_limit"),
    /** 被取消。 */
    CANCELLED("cancelled"),
    /** 游戏自身给出的原因，原样转述（已做过空白与长度清洗）。 */
    GAME_REASON("game_reason"),
    /** 认不出来的失败。 */
    FAILED("failed");

    private final String id;

    DubheActionFailure(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The reply-text resource key for this cause. */
    public String resourceKey() {
        return "action.failure." + id;
    }

    /**
     * Maps a tool error code (and, for the server ceiling, its message) onto the player-facing cause.
     */
    public static DubheActionFailure fromToolError(String code, String message) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "NO_PERMISSION", "UNSUPPORTED_ACTOR", "UNSUPPORTED_SIDE" -> NOT_ALLOWED;
            case "UNKNOWN_TOOL", "NOT_FOUND" -> TARGET_UNKNOWN;
            case "DISABLED", "UNAVAILABLE" -> TARGET_OFF;
            case "BUSY" -> TARGET_BUSY;
            case "TIMEOUT" -> TIME_LIMIT;
            case "RATE_LIMITED" -> TOO_OFTEN;
            case "CANCELLED" -> CANCELLED;
            case "GAME_REASON" -> GAME_REASON;
            default -> text.contains("ceiling") ? SERVER_LIMIT : FAILED;
        };
    }
}