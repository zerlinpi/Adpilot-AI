package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.model.PlatformWriteResult;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the {@link PlatformWriteResult} factory methods and
 * response classification logic.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification
 *
 * <p><b>Validates: Requirements 1.8, 1.9, 1.11, 1.12, 16.5, 30.7</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>{@code acceptedAmazon(requestId, entityId, msg)} → accepted=true, retryable=false,
 *       carries structured requestId and entityId</li>
 *   <li>{@code retryable(reason, retryAfter)} → accepted=false, retryable=true,
 *       carries retryAfterSeconds</li>
 *   <li>{@code permanentReject(errorCode, reason)} → accepted=false, retryable=false,
 *       carries platformErrorCode</li>
 *   <li>Accepted results never have retryable=true</li>
 *   <li>Retryable results never have accepted=true</li>
 *   <li>Rejected results carry the error code in the structured field (not packed into message)</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification")
class ConnectorResponseClassificationPropertyTest {

    // ────────────────────────────────────────────────────────────────────────────
    // Property 1: acceptedAmazon produces correct classification
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification
     *
     * <p><b>Validates: Requirements 1.11, 16.5</b>
     *
     * <p>{@code acceptedAmazon(requestId, entityId, msg)} always returns a result with
     * accepted=true, retryable=false, and carries the structured requestId and entityId.
     */
    @Property(tries = 150)
    void acceptedAmazonProducesAcceptedWithStructuredFields(
            @ForAll("amazonRequestIds") String requestId,
            @ForAll("externalEntityIds") String entityId,
            @ForAll("messages") String message) {

        PlatformWriteResult result = PlatformWriteResult.acceptedAmazon(requestId, entityId, message);

        assertThat(result.accepted())
                .as("acceptedAmazon must produce accepted=true")
                .isTrue();
        assertThat(result.retryable())
                .as("acceptedAmazon must produce retryable=false")
                .isFalse();
        assertThat(result.amazonRequestId())
                .as("acceptedAmazon must carry the structured Amazon request ID")
                .isEqualTo(requestId);
        assertThat(result.externalEntityId())
                .as("acceptedAmazon must carry the structured external entity ID")
                .isEqualTo(entityId);
        assertThat(result.message())
                .as("acceptedAmazon must carry the provided message")
                .isEqualTo(message);
        assertThat(result.platformErrorCode())
                .as("acceptedAmazon must not carry a platform error code")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 2: retryable produces correct classification
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification
     *
     * <p><b>Validates: Requirements 1.8, 1.9, 30.7</b>
     *
     * <p>{@code retryable(reason, retryAfter)} always returns a result with
     * accepted=false, retryable=true, and carries retryAfterSeconds.
     */
    @Property(tries = 150)
    void retryableProducesRetryableWithBackoff(
            @ForAll("messages") String reason,
            @ForAll("retryAfterValues") Long retryAfterSeconds) {

        PlatformWriteResult result = PlatformWriteResult.retryable(reason, retryAfterSeconds);

        assertThat(result.accepted())
                .as("retryable must produce accepted=false")
                .isFalse();
        assertThat(result.retryable())
                .as("retryable must produce retryable=true")
                .isTrue();
        assertThat(result.retryAfterSeconds())
                .as("retryable must carry the retryAfterSeconds value")
                .isEqualTo(retryAfterSeconds);
        assertThat(result.message())
                .as("retryable must carry the reason as message")
                .isEqualTo(reason);
        assertThat(result.amazonRequestId())
                .as("retryable must not carry an Amazon request ID")
                .isNull();
        assertThat(result.externalEntityId())
                .as("retryable must not carry an external entity ID")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 3: permanentReject produces correct classification
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification
     *
     * <p><b>Validates: Requirements 1.12, 16.5</b>
     *
     * <p>{@code permanentReject(errorCode, reason)} always returns a result with
     * accepted=false, retryable=false, and carries the platformErrorCode.
     */
    @Property(tries = 150)
    void permanentRejectProducesNonRetryableRejectionWithErrorCode(
            @ForAll("errorCodes") String errorCode,
            @ForAll("messages") String reason) {

        PlatformWriteResult result = PlatformWriteResult.permanentReject(errorCode, reason);

        assertThat(result.accepted())
                .as("permanentReject must produce accepted=false")
                .isFalse();
        assertThat(result.retryable())
                .as("permanentReject must produce retryable=false")
                .isFalse();
        assertThat(result.platformErrorCode())
                .as("permanentReject must carry the platform error code")
                .isEqualTo(errorCode);
        assertThat(result.message())
                .as("permanentReject must carry the reason as message")
                .isEqualTo(reason);
        assertThat(result.retryAfterSeconds())
                .as("permanentReject must not carry retryAfterSeconds")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4: Accepted results never have retryable=true
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification
     *
     * <p><b>Validates: Requirements 1.8, 1.11, 16.5</b>
     *
     * <p>For every accepted result produced by any factory method, the retryable
     * flag is always false. Acceptance and retryability are mutually exclusive.
     */
    @Property(tries = 150)
    void acceptedResultsNeverHaveRetryableTrue(
            @ForAll("amazonRequestIds") String requestId,
            @ForAll("externalEntityIds") String entityId,
            @ForAll("messages") String message) {

        // Test via acceptedAmazon
        PlatformWriteResult amazonResult = PlatformWriteResult.acceptedAmazon(requestId, entityId, message);
        assertThat(amazonResult.accepted()).isTrue();
        assertThat(amazonResult.retryable())
                .as("An accepted result (acceptedAmazon) must never be retryable")
                .isFalse();

        // Test via legacy accepted factory
        PlatformWriteResult legacyResult = PlatformWriteResult.accepted(requestId, message);
        assertThat(legacyResult.accepted()).isTrue();
        assertThat(legacyResult.retryable())
                .as("An accepted result (legacy accepted) must never be retryable")
                .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 5: Retryable results never have accepted=true
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification
     *
     * <p><b>Validates: Requirements 1.8, 1.9, 30.7</b>
     *
     * <p>For every retryable result, the accepted flag is always false.
     * A result cannot be both accepted and retryable.
     */
    @Property(tries = 150)
    void retryableResultsNeverHaveAcceptedTrue(
            @ForAll("messages") String reason,
            @ForAll("retryAfterValues") Long retryAfterSeconds) {

        PlatformWriteResult result = PlatformWriteResult.retryable(reason, retryAfterSeconds);

        assertThat(result.retryable()).isTrue();
        assertThat(result.accepted())
                .as("A retryable result must never be accepted")
                .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 6: Rejected results carry error code in structured field
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 1: Connector response classification
     *
     * <p><b>Validates: Requirements 1.12, 16.5</b>
     *
     * <p>The platform error code on a permanent rejection is carried in the dedicated
     * {@code platformErrorCode} field, NOT packed into the free-text message string.
     * The message contains the human-readable reason, and the error code is independently
     * accessible via its own accessor.
     */
    @Property(tries = 150)
    void rejectedResultsCarryErrorCodeInStructuredField(
            @ForAll("errorCodes") String errorCode,
            @ForAll("messages") String reason) {

        PlatformWriteResult result = PlatformWriteResult.permanentReject(errorCode, reason);

        // The error code is in the structured field
        assertThat(result.platformErrorCode())
                .as("Error code must be in the dedicated platformErrorCode field")
                .isEqualTo(errorCode)
                .isNotNull();

        // The message is the human-readable reason, separate from the error code
        assertThat(result.message())
                .as("Message must be the human-readable reason")
                .isEqualTo(reason);

        // The error code is independently accessible — not only derivable from parsing the message
        // (i.e., even if the message happens to contain the error code text, the structured field
        // is the authoritative source)
        assertThat(result.platformErrorCode())
                .as("platformErrorCode field must be the authoritative location for the error code")
                .isNotEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Amazon request IDs — realistic UUID-like or alphanumeric identifiers.
     */
    @Provide
    Arbitrary<String> amazonRequestIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .numeric()
                .withChars('-')
                .ofMinLength(8)
                .ofMaxLength(40);
    }

    /**
     * External entity IDs — numeric or alphanumeric strings representing Amazon entity IDs.
     */
    @Provide
    Arbitrary<String> externalEntityIds() {
        return Arbitraries.strings()
                .numeric()
                .ofMinLength(5)
                .ofMaxLength(20);
    }

    /**
     * Human-readable messages or reasons.
     */
    @Provide
    Arbitrary<String> messages() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withChars(' ', '.', '-', '_')
                .ofMinLength(1)
                .ofMaxLength(100);
    }

    /**
     * Retry-after values — nullable Long representing seconds to wait.
     * Includes null (no hint), 0, and realistic positive values.
     */
    @Provide
    Arbitrary<Long> retryAfterValues() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.longs().between(0L, 300L)
        );
    }

    /**
     * Platform error codes — uppercase snake-case identifiers typical of Amazon Ads errors.
     */
    @Provide
    Arbitrary<String> errorCodes() {
        return Arbitraries.of(
                "INVALID_BID_AMOUNT",
                "CAMPAIGN_NOT_FOUND",
                "KEYWORD_ALREADY_EXISTS",
                "BUDGET_BELOW_MINIMUM",
                "INVALID_STATE_TRANSITION",
                "NO_EXTERNAL_MAPPING",
                "TOKEN_INVALID",
                "INVALID_MATCH_TYPE",
                "AD_GROUP_NOT_FOUND",
                "DUPLICATE_KEYWORD"
        );
    }
}
