package com.adpilot.modules.apisync.vo;

import lombok.Getter;

/**
 * Result wrapper for TikTok Ads read operations. Mirrors
 * {@link GoogleAdsReadResult}: carries one of three states so the frontend can
 * distinguish a successful retrieval from the connect-prompt and error signals
 * the TikTok Ads read surface must expose, honouring the project's honesty
 * principle (real fetch; never fabricated data).
 *
 * <ul>
 *   <li>{@link State#OK} — retrieval succeeded; {@link #getData()} holds the payload.</li>
 *   <li>{@link State#CONNECT_PROMPT} — the selected Store has no active TikTok Ads
 *       Platform_Connection, so the UI shows a connect prompt rather than an error.</li>
 *   <li>{@link State#ERROR} — retrieval failed; {@link #getMessage()} holds the
 *       reason and the caller leaves any previously displayed data unchanged.</li>
 * </ul>
 *
 * @param <T> payload type for the {@link State#OK} state
 */
@Getter
public class TikTokAdsReadResult<T> {

    public enum State {
        /** Retrieval succeeded; {@link #getData()} is populated. */
        OK,
        /** No active TikTok Ads connection for the Store; show a connect prompt. */
        CONNECT_PROMPT,
        /** Retrieval failed; {@link #getMessage()} explains why. */
        ERROR
    }

    private final State state;
    private final T data;
    private final String message;

    private TikTokAdsReadResult(State state, T data, String message) {
        this.state = state;
        this.data = data;
        this.message = message;
    }

    public static <T> TikTokAdsReadResult<T> ok(T data) {
        return new TikTokAdsReadResult<>(State.OK, data, null);
    }

    public static <T> TikTokAdsReadResult<T> connectPrompt() {
        return new TikTokAdsReadResult<>(State.CONNECT_PROMPT, null,
                "未连接 TikTok Ads：请先为该独立站店铺绑定 TikTok Ads 广告账号");
    }

    public static <T> TikTokAdsReadResult<T> error(String message) {
        return new TikTokAdsReadResult<>(State.ERROR, null, message);
    }

    public boolean isOk() {
        return state == State.OK;
    }

    public boolean isConnectPrompt() {
        return state == State.CONNECT_PROMPT;
    }

    public boolean isError() {
        return state == State.ERROR;
    }
}
