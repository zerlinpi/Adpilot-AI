package com.adpilot.modules.user.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the credential fields on {@link User} are never leaked in JSON
 * responses while still being accepted on input.
 *
 * <p>{@code passwordHash} and {@code twofaSecret} are annotated
 * {@code @JsonProperty(access = WRITE_ONLY)} so the {@code GET /api/users} and
 * {@code GET /api/users/{id}} endpoints, which serialize the raw entity, cannot
 * expose bcrypt hashes or 2FA secrets, while {@code POST /api/users} can still
 * read the password from the deserialized request body.</p>
 */
class UserJsonSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("serialized User JSON omits passwordHash and twofaSecret")
    void serializationOmitsCredentialFields() throws Exception {
        User user = User.builder()
                .id(UUID.randomUUID())
                .orgId(UUID.randomUUID())
                .email("secure@example.com")
                .name("Secure User")
                .passwordHash("$2a$10$super-secret-bcrypt-hash")
                .twofaSecret("ENCRYPTED-TOTP-SECRET")
                .twofaEnabled(true)
                .build();

        String json = objectMapper.writeValueAsString(user);

        assertThat(json)
                .as("the raw JSON must not contain the credential property names or values")
                .doesNotContain("passwordHash")
                .doesNotContain("$2a$10$super-secret-bcrypt-hash")
                .doesNotContain("twofaSecret")
                .doesNotContain("ENCRYPTED-TOTP-SECRET");

        // Non-sensitive fields are still serialized so the API remains useful.
        assertThat(json).contains("secure@example.com");
    }

    @Test
    @DisplayName("credential fields are still deserialized from input (createUser flow)")
    void deserializationStillAcceptsCredentialFields() throws Exception {
        String json = "{"
                + "\"email\":\"secure@example.com\","
                + "\"name\":\"Secure User\","
                + "\"passwordHash\":\"PlaintextPassword123!\","
                + "\"twofaSecret\":\"ENCRYPTED-TOTP-SECRET\""
                + "}";

        User user = objectMapper.readValue(json, User.class);

        assertThat(user.getPasswordHash())
                .as("WRITE_ONLY still allows the value to be bound from the request body")
                .isEqualTo("PlaintextPassword123!");
        assertThat(user.getTwofaSecret()).isEqualTo("ENCRYPTED-TOTP-SECRET");
    }
}
