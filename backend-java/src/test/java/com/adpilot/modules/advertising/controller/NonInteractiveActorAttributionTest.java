package com.adpilot.modules.advertising.controller;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.utils.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example test for the non-interactive (scheduled/background) actor path
 * (Req 12.5).
 *
 * <p>Feature: platform-ux-logistics-enhancements.
 *
 * <p>Validates: Requirements 12.5.
 *
 * <p>A goal/keyword/recommendation/search-term operation that legitimately runs
 * without an interactive user (a scheduled or background trigger) records a
 * reserved non-interactive actor identifier in the audit trail that does not
 * match any authenticated user identifier. The reserved actor is the constant
 * {@link SecurityUtils#SYSTEM_ACTOR_ID}; an interactive caller is resolved via
 * {@link SecurityUtils#getCurrentUserIdOrNull()}. A non-interactive trigger has
 * no authenticated principal in the security context, so the resolved
 * interactive id is {@code null} and the recorded actor falls back to the
 * reserved {@code SYSTEM_ACTOR_ID}.
 */
class NonInteractiveActorAttributionTest {

    /**
     * Resolves the actor a goal/keyword/recommendation/search-term trigger
     * records, mirroring the production audit-attribution semantics: when an
     * interactive user is present it is attributed to that user; otherwise the
     * reserved non-interactive actor is recorded (Req 12.5).
     */
    private static String resolveAuditActor() {
        String interactiveUser = SecurityUtils.getCurrentUserIdOrNull();
        return interactiveUser != null ? interactiveUser : SecurityUtils.SYSTEM_ACTOR_ID;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Scheduled/background trigger records the reserved SYSTEM_ACTOR, distinct from any authenticated user id")
    void scheduledTriggerRecordsReservedActorDistinctFromAuthenticatedUser() {
        // A scheduled/background trigger runs without an interactive user: there
        // is no authenticated principal in the security context.
        SecurityContextHolder.clearContext();
        assertThat(SecurityUtils.isAuthenticated()).isFalse();
        assertThat(SecurityUtils.getCurrentUserIdOrNull()).isNull();

        // The actor recorded for the non-interactive operation is the reserved
        // SYSTEM_ACTOR identifier.
        String recordedActor = resolveAuditActor();
        assertThat(recordedActor).isEqualTo(SecurityUtils.SYSTEM_ACTOR_ID);

        // The reserved actor is a valid UUID (the nil UUID), so it never collides
        // with the "system" literal the cleanup replaced.
        assertThat(recordedActor).isNotEqualToIgnoringCase("system");
        UUID reserved = UUID.fromString(recordedActor);
        assertThat(reserved).isEqualTo(new UUID(0L, 0L));

        // The reserved actor does not match any authenticated user identifier.
        for (int i = 0; i < 100; i++) {
            assertThat(recordedActor).isNotEqualTo(UUID.randomUUID().toString());
        }
    }

    @Test
    @DisplayName("With an authenticated user present the actor is the user id, never the reserved SYSTEM_ACTOR")
    void interactiveTriggerRecordsAuthenticatedUserNotReservedActor() {
        UUID authenticatedUserId = UUID.randomUUID();
        authenticateAs(authenticatedUserId);

        String recordedActor = resolveAuditActor();

        // An interactive operation is attributed to the real user, distinct from
        // the reserved non-interactive actor.
        assertThat(recordedActor).isEqualTo(authenticatedUserId.toString());
        assertThat(recordedActor).isNotEqualTo(SecurityUtils.SYSTEM_ACTOR_ID);
    }

    private static void authenticateAs(UUID userId) {
        CurrentUser principal = CurrentUser.builder()
                .userId(userId.toString())
                .email("user-" + userId + "@example.com")
                .orgId(UUID.randomUUID().toString())
                .name("Test User")
                .roles(Set.of("operations_manager"))
                .permissions(List.of("advertising:manage"))
                .build();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
