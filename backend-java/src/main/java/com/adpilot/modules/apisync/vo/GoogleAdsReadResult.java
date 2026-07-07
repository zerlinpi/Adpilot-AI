package com.adpilot.modules.apisync.vo;

import lombok.Getter;

/**
 * Result wrapper for Google Ads read operations (platform-workspace-rbac Req 6).
 *
 * <p>Carries one of three states so the frontend can distinguish a successful
 * retrieval from the two non-error / error signals the GoogleAds_Module must
 * surface:</p>
 * <ul>
 *   <li>{@link State#OK} — retrieval succeeded; {@link #getData()} holds the payload (Req 6.3, 6.4).</li>
 *   <li>{@link State#CONNECT_PROMPT} — the selected Store has no active Google Ads
 *       Platform_Connection, so the UI shows a connect prompt rather than an error (Req 6.6).</li>
 *   <li>{@link State#ERROR} — retrieval failed; {@link #getMessage()} holds the reason and
 *       the caller leaves any previously displayed data unchanged (Req 6.5).</li>
 * </ul>
 *
 * @param <T> payload type for the {@link State#OK} state
 */
@Getter
public class GoogleAdsReadResult<T> {

    public enum State {
        /** Retrieval succeeded; {@link #getData()} is populated. */
        OK,
        /** No active Google Ads connection for the Store; show a connect prompt (Req 6.6). */
        CONNECT_PROMPT,
        /** Retrieval failed; {@link #getMessage()} explains why (Req 6.5). */
        ERROR
    }

    private final State state;
    private final T data;
    private final String message;

    private GoogleAdsReadResult(State state, T data, String message) {
        this.state = state;
        this.data = data;
        this.message = message;
    }

    public static <T> GoogleAdsReadResult<T> ok(T data) {
        return new GoogleAdsReadResult<>(State.OK, data, null);
    }

    public static <T> GoogleAdsReadResult<T> connectPrompt() {
        return new GoogleAdsReadResult<>(State.CONNECT_PROMPT, null,
                "未连接 Google Ads：请先为该独立站店铺绑定 Google Ads 账号");
    }

    public static <T> GoogleAdsReadResult<T> error(String message) {
        return new GoogleAdsReadResult<>(State.ERROR, null, message);
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
