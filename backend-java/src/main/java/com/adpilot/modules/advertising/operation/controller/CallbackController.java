package com.adpilot.modules.advertising.operation.controller;

import com.adpilot.modules.advertising.operation.callback.CallbackOutcome;
import com.adpilot.modules.advertising.operation.callback.CallbackSignatureVerifier;
import com.adpilot.modules.advertising.operation.callback.OperationCallbackService;
import com.adpilot.modules.advertising.operation.callback.PlatformCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives inbound platform callbacks that report the current status of a previously submitted
 * {@code platform_mutation} Operation, and drives the corresponding idempotent Sync_State transition
 * (Req 4, 5.7, 53.2, 55.5, 55.6).
 *
 * <p><b>Network-exposed, server-to-server endpoint.</b> The platform's servers — not an
 * authenticated operator — call this endpoint, so it is permitted past JWT authentication in
 * {@code SecurityConfig}. Its ONLY authenticity gate is the mandatory signature verification below.
 * The signature is verified BEFORE any Operation state is read or mutated (Req 55.5): a callback
 * whose signature cannot be verified is rejected with {@code 401} and mutates no Operation
 * (Property 77). Because verification fails closed when no signing secret is configured, an
 * unconfigured deployment safely rejects all callbacks rather than trusting unsigned traffic.</p>
 *
 * <p>After verification, processing is delegated to {@link OperationCallbackService}, which
 * correlates the callback to its Operation by {@code submissionIdempotencyKey} (Req 5.7), refuses to
 * process callbacks for a not-write-capable Store (Req 53.2, Property 12), and applies the transition
 * idempotently so a re-delivered result yields the same final state (Req 5.7, 55.6, Property 18).</p>
 *
 * <p>Validates: Requirements 55.5, 55.6, 5.7, 53.2.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/advertising/operations")
@RequiredArgsConstructor
public class CallbackController {

    /** Header carrying the platform key the callback claims to originate from. */
    static final String HEADER_PLATFORM = "X-AdPilot-Platform";
    /** Header carrying the caller's signature over {@code timestamp + "." + rawBody}. */
    static final String HEADER_SIGNATURE = "X-AdPilot-Signature";
    /** Header carrying the timestamp the signature was computed over. */
    static final String HEADER_TIMESTAMP = "X-AdPilot-Timestamp";

    private final CallbackSignatureVerifier signatureVerifier;
    private final OperationCallbackService operationCallbackService;
    private final ObjectMapper objectMapper;

    /**
     * POST /api/advertising/operations/callback — accept a signed platform callback.
     *
     * @param platformHeader the platform key (optional; falls back to the body's {@code platform})
     * @param signature      the caller's signature header (required for acceptance)
     * @param timestamp      the timestamp header the signature was computed over
     * @param rawBody        the exact raw request body the signature was computed over
     * @return {@code 401} when the signature cannot be verified (no mutation), otherwise {@code 200}
     *         with the (non-mutating or applied) processing outcome
     */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestHeader(value = HEADER_PLATFORM, required = false) String platformHeader,
            @RequestHeader(value = HEADER_SIGNATURE, required = false) String signature,
            @RequestHeader(value = HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestBody(required = false) String rawBody) {

        JsonNode body = parse(rawBody);
        String platform = hasText(platformHeader) ? platformHeader : text(body, "platform");

        // MANDATORY: verify authenticity/signature BEFORE reading or mutating any Operation state.
        // An unverifiable callback is rejected and mutates nothing (Req 55.5, Property 77).
        if (!signatureVerifier.verify(platform, timestamp, signature, rawBody)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "verified", false,
                    "message", "callback signature verification failed"));
        }

        PlatformCallback callback = new PlatformCallback(
                platform,
                text(body, "submissionIdempotencyKey"),
                text(body, "platformReference"),
                text(body, "platformStatus"),
                text(body, "message"));

        CallbackOutcome outcome = operationCallbackService.process(callback);
        return ResponseEntity.ok(Map.of(
                "verified", true,
                "outcome", outcome.name()));
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
