package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Transport envelope for a {@link TikTokAdsReadResult} over HTTP. Mirrors
 * {@link GoogleAdsReadResponse}.
 *
 * <p>The three read states are carried as a {@code state} string rather than
 * mapped onto HTTP status codes, because two of them are <em>not</em> transport
 * failures:</p>
 * <ul>
 *   <li>{@code OK} — {@link #getData()} holds the payload.</li>
 *   <li>{@code CONNECT_PROMPT} — the Store has no active TikTok Ads connection,
 *       so the UI shows a connect prompt rather than an error.</li>
 *   <li>{@code ERROR} — retrieval failed; {@link #getMessage()} carries the
 *       reason and the UI shows an error indicator + retry control while leaving
 *       previously displayed data unchanged.</li>
 * </ul>
 *
 * @param <T> payload type for the {@code OK} state
 */
@Data
@Builder
public class TikTokAdsReadResponse<T> {

    /** One of {@code OK}, {@code CONNECT_PROMPT}, {@code ERROR}. */
    private String state;

    /** Payload, present only when {@code state == OK}. */
    private T data;

    /** Human-readable message for the {@code CONNECT_PROMPT} / {@code ERROR} states. */
    private String message;

    /** Adapt a {@link TikTokAdsReadResult} to its HTTP transport envelope. */
    public static <T> TikTokAdsReadResponse<T> from(TikTokAdsReadResult<T> result) {
        return TikTokAdsReadResponse.<T>builder()
                .state(result.getState().name())
                .data(result.getData())
                .message(result.getMessage())
                .build();
    }
}
