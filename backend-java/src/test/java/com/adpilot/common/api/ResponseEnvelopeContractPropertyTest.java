package com.adpilot.common.api;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.exception.GlobalExceptionHandler;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the API response envelope contract.
 *
 * Feature: app-functionality-completion, Property 1: Response envelope contract.
 *
 * <p>For any success payload the envelope sets {@code success=true} with the
 * payload under {@code data} and no {@code error}. For any thrown domain or
 * generic error, and for any undefined {@code /api} route, the envelope sets
 * {@code success=false} with a non-empty {@code error.code} and an
 * {@code error.message} of length 1–500, and the HTTP status is in the 4xx
 * (client) or 5xx (server) range.
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 1.5.
 *
 * <p>These exercise the pure envelope factory ({@link ApiResponse}) together
 * with the handler beans ({@link GlobalExceptionHandler}, {@link ApiNotFoundController})
 * directly — no Spring context is needed because each unit deterministically
 * maps an input to a {@link ResponseEntity} carrying the envelope.
 */
class ResponseEnvelopeContractPropertyTest {

    private final GlobalExceptionHandler exceptionHandler =
            new GlobalExceptionHandler(new org.springframework.mock.env.MockEnvironment());
    private final ApiNotFoundController notFoundController = new ApiNotFoundController();

    /**
     * Feature: app-functionality-completion, Property 1: Response envelope contract.
     *
     * Req 1.1: a successful response sets {@code success=true} with the payload
     * carried under {@code data} and no {@code error} object.
     */
    @Property(tries = 200)
    void successEnvelopeCarriesPayloadUnderData(@ForAll("payloads") String payload) {
        ApiResponse<String> response = ApiResponse.ok(payload);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(payload);
        assertThat(response.getError()).isNull();
    }

    /**
     * Feature: app-functionality-completion, Property 1: Response envelope contract.
     *
     * Req 1.2: a failure envelope sets {@code success=false} and carries a
     * non-empty {@code error.code} and a {@code error.message} of length 1–500.
     */
    @Property(tries = 200)
    void failEnvelopeCarriesNonEmptyError(
            @ForAll("errorCodes") String code,
            @ForAll("errorMessages") String message) {
        ApiResponse<Void> response = ApiResponse.fail(code, message);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).isNotNull();
        assertThat(response.getError().getCode()).isNotEmpty();
        assertThat(response.getError().getMessage()).isNotEmpty();
        assertThat(response.getError().getMessage().length()).isBetween(1, 500);
    }

    /**
     * Feature: app-functionality-completion, Property 1: Response envelope contract.
     *
     * Req 1.2 / 1.3: any thrown {@link BusinessException} carrying a 4xx/5xx
     * status, a non-empty code, and a 1–500 char message is mapped to a JSON
     * envelope with {@code success=false}, the same code/message, and the same
     * 4xx/5xx HTTP status — never an HTML error page.
     */
    @Property(tries = 200)
    void businessExceptionMapsToWellFormedErrorEnvelope(
            @ForAll("clientServerStatuses") int status,
            @ForAll("errorCodes") String code,
            @ForAll("errorMessages") String message) {

        BusinessException ex = new BusinessException(status, code, message);

        ResponseEntity<ApiResponse<Void>> entity = exceptionHandler.handleBusinessException(ex);

        // HTTP status is in the 4xx (client) or 5xx (server) range.
        assertThat(entity.getStatusCode().value()).isBetween(400, 599);
        assertThat(entity.getStatusCode().value()).isEqualTo(status);

        ApiResponse<Void> body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getError()).isNotNull();
        assertThat(body.getError().getCode()).isNotEmpty().isEqualTo(code);
        assertThat(body.getError().getMessage()).isNotEmpty().isEqualTo(message);
        assertThat(body.getError().getMessage().length()).isBetween(1, 500);
    }

    /**
     * Feature: app-functionality-completion, Property 1: Response envelope contract.
     *
     * Req 1.3: any unhandled server exception is mapped to a JSON envelope with
     * {@code success=false}, a non-empty code, a 1–500 char message, and a 5xx
     * status — never an HTML error page.
     */
    @Property(tries = 200)
    void genericExceptionMapsToServerErrorEnvelope(@ForAll("errorMessages") String rawMessage) {
        Exception ex = new RuntimeException(rawMessage);

        ResponseEntity<ApiResponse<Void>> entity = exceptionHandler.handleGenericException(ex);

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(entity.getStatusCode().value()).isBetween(500, 599);

        ApiResponse<Void> body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getError()).isNotNull();
        assertThat(body.getError().getCode()).isNotEmpty();
        assertThat(body.getError().getMessage()).isNotEmpty();
        assertThat(body.getError().getMessage().length()).isBetween(1, 500);
    }

    /**
     * Feature: app-functionality-completion, Property 1: Response envelope contract.
     *
     * Req 1.5: any undefined {@code /api} route is mapped to a JSON 404 envelope
     * with {@code success=false}, a non-empty {@code error.code}, and a 1–500
     * char message — never an HTML page.
     */
    @Property(tries = 200)
    void undefinedApiRouteMapsToJsonNotFoundEnvelope(
            @ForAll("httpMethods") String method,
            @ForAll("apiPaths") String path) {

        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);

        ResponseEntity<ApiResponse<Void>> entity = notFoundController.handleUnmatchedApiRoute(request);

        assertThat(entity.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(entity.getStatusCode().value()).isBetween(400, 599);

        ApiResponse<Void> body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getError()).isNotNull();
        assertThat(body.getError().getCode()).isNotEmpty();
        assertThat(body.getError().getMessage()).isNotEmpty();
        assertThat(body.getError().getMessage().length()).isBetween(1, 500);
    }

    // ---- Generators (constrained to the realistic API input space) ----

    /** Arbitrary success payloads, including empty strings. */
    @Provide
    Arbitrary<String> payloads() {
        return Arbitraries.strings().ofMaxLength(200);
    }

    /** Non-empty, machine-readable error codes (letters, digits, underscores). */
    @Provide
    Arbitrary<String> errorCodes() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .withChars('0', '9', '_')
                .ofMinLength(1)
                .ofMaxLength(40);
    }

    /** Human-readable error messages of 1–500 characters (the contract bound). */
    @Provide
    Arbitrary<String> errorMessages() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(500)
                .filter(s -> !s.isEmpty());
    }

    /** HTTP statuses spanning the 4xx client and 5xx server error ranges. */
    @Provide
    Arbitrary<Integer> clientServerStatuses() {
        return Arbitraries.integers().between(400, 599);
    }

    /** Common HTTP request methods. */
    @Provide
    Arbitrary<String> httpMethods() {
        return Arbitraries.of("GET", "POST", "PUT", "PATCH", "DELETE");
    }

    /** Undefined but well-formed {@code /api} request paths. */
    @Provide
    Arbitrary<String> apiPaths() {
        Arbitrary<String> segment = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '0', '9')
                .ofMinLength(1)
                .ofMaxLength(20);
        return segment.list().ofMinSize(1).ofMaxSize(5)
                .map(parts -> "/api/" + String.join("/", parts));
    }
}
