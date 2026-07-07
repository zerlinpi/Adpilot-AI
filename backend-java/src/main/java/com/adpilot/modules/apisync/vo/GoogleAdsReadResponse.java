package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Transport envelope for a {@link GoogleAdsReadResult} over HTTP
 * (platform-workspace-rbac Req 6).
 *
 * <p>The three read states the GoogleAds_Module must distinguish are carried as
 * a {@code state} string rather than mapped onto HTTP status codes, because two
 * of them are <em>not</em> transport failures:</p>
 * <ul>
 *   <li>{@code OK} — {@link #getData()} holds the payload (Req 6.3, 6.4).</li>
 *   <li>{@code CONNECT_PROMPT} — the Store has no active Google Ads connection,
 *       so the UI shows a connect prompt rather than an error (Req 6.6).</li>
 *   <li>{@code ERROR} — retrieval failed; {@link #getMessage()} carries the
 *       reason and the UI shows an error indicator with a retry control while
 *       leaving previously displayed data unchanged (Req 6.5).</li>
 * </ul>
 *
 * <p>Returning all three as a successful {@code ApiResponse} envelope lets the
 * frontend branch on {@code state} deterministically; genuine transport/auth
 * failures (e.g. 403 Cross_Platform_Access) still arrive as HTTP errors and are
 * surfaced by the shared API client (Req 17.4).</p>
 *
 * @param <T> payload type for the {@code OK} state
 */
@Data
@Builder
public class GoogleAdsReadResponse<T> {

    /** One of {@code OK}, {@code CONNECT_PROMPT}, {@code ERROR}. */
    private String state;

    /** Payload, present only when {@code state == OK}. */
    private T data;

    /** Human-readable message for the {@code CONNECT_PROMPT} / {@code ERROR} states. */
    private String message;

    /** Adapt a {@link GoogleAdsReadResult} to its HTTP transport envelope. */
    public static <T> GoogleAdsReadResponse<T> from(GoogleAdsReadResult<T> result) {
        return GoogleAdsReadResponse.<T>builder()
                .state(result.getState().name())
                .data(result.getData())
                .message(result.getMessage())
                .build();
    }
}
