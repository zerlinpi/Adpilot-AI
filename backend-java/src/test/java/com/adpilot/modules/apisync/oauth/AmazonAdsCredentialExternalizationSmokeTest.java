package com.adpilot.modules.apisync.oauth;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke tests for Amazon Ads credential externalization (Req 15.1, 15.4).
 *
 * <p>Req 15.1: no literal client-id/secret is committed — both must be sourced
 * from environment variables and default to blank when unset.</p>
 *
 * <p>Req 15.4: a blank-credential operation must fail gracefully with a clear
 * 4xx ("credentials not configured") instead of a 500/NPE.</p>
 */
class AmazonAdsCredentialExternalizationSmokeTest {

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    // ── Req 15.1: committed config externalizes credentials, no literal secret ──

    @Test
    void committedConfigBindsCredentialsToEnvironmentVariables() throws IOException {
        String yml = readApplicationYml();

        // client-id / client-secret must be ${ENV:} bindings, not literal values.
        assertThat(yml).contains("client-id: ${ADPILOT_AMAZON_ADS_CLIENT_ID:}");
        assertThat(yml).contains("client-secret: ${ADPILOT_AMAZON_ADS_CLIENT_SECRET:}");
    }

    @Test
    void committedConfigContainsNoLiteralAmazonClientCredential() throws IOException {
        List<String> lines = Files.readAllLines(APPLICATION_YML, StandardCharsets.UTF_8);

        // The only "amzn1." token allowed is inside explanatory comments. No
        // configuration *value* line may carry a literal Amazon client id/secret.
        Pattern amazonLiteral = Pattern.compile("amzn1\\.");
        for (String line : lines) {
            String code = stripComment(line);
            assertThat(amazonLiteral.matcher(code).find())
                    .as("config line must not contain a literal amzn1.* credential: <%s>", line)
                    .isFalse();
        }
    }

    // ── Req 15.4: blank-credential operation surfaces a 4xx, never a 500 ────────

    @Test
    void blankCredentialAuthorizeThrowsNotConfiguredBusinessException() {
        AmazonAdsProperties blankProps = new AmazonAdsProperties(); // defaults: all blank
        assertThat(blankProps.isConfigured()).isFalse();

        AmazonAdsOAuthService service = new AmazonAdsOAuthService(
                blankProps, null, null, null, new ObjectMapper(), null, null, null, null, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        assertThatThrownBy(() -> service.buildAuthorizeUrl("na", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("AMAZON_ADS_NOT_CONFIGURED");
                    // 4xx, not 5xx.
                    assertThat(be.getStatus()).isBetween(400, 499);
                });
    }

    @Test
    void notConfiguredBusinessExceptionMapsToClientErrorResponse() {
        BusinessException ex =
                new BusinessException("AMAZON_ADS_NOT_CONFIGURED", "credentials not configured");

        ResponseEntity<ApiResponse<Void>> response =
                new GlobalExceptionHandler(new org.springframework.mock.env.MockEnvironment())
                        .handleBusinessException(ex);

        // Maps to a 4xx (client error), never a 5xx.
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getStatusCode().is5xxServerError()).isFalse();
        ApiResponse<Void> body = Objects.requireNonNull(response.getBody());
        assertThat(body.isSuccess()).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static String readApplicationYml() throws IOException {
        assertThat(Files.exists(APPLICATION_YML))
                .as("committed application.yml must exist at %s", APPLICATION_YML.toAbsolutePath())
                .isTrue();
        return Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
    }

    /** Drop the trailing YAML comment (everything from the first '#') from a line. */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }
}
