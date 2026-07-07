package com.adpilot.modules.auth.service.impl;

import com.adpilot.modules.user.entity.LoginLog;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the login-logging behaviour of
 * {@link LoginSecurityServiceImpl}.
 *
 * Feature: core-platform-completion, Property 24: Every login attempt is logged
 * with its outcome.
 *
 * <p>For any sequence of login attempts — each being one of the five recordable
 * outcomes (unknown user, locked account, failed credentials, rejected
 * non-credential failure, or successful login) — exactly one login-log entry is
 * written per attempt, and that entry records the attempt's outcome: a
 * {@code success} status with no failure reason for a successful login, and a
 * {@code failed} status with a non-blank failure reason for every other
 * outcome. Thus the number of persisted entries always equals the number of
 * attempts, with no attempt left unlogged and no spurious extra entries.</p>
 *
 * <p>{@link UserMapper} and {@link LoginLogMapper} are mocked; the login-log
 * mapper captures every inserted {@link LoginLog} into an in-memory list so the
 * one-entry-per-attempt invariant can be checked after each step without a
 * database, mirroring the mocking style used by the other property tests in
 * this module.</p>
 *
 * Validates: Requirements 11.1.4
 */
class LoginLoggingPropertyTest {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";

    private static final String IP = "203.0.113.7";
    private static final String UA = "JUnit-Agent/1.0";

    static {
        // The service builds LambdaUpdateWrapper<User> instances, which require
        // MyBatis-Plus entity metadata that is normally populated during Spring
        // mapper scanning. Register it once for this standalone (no-context) test.
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    /** The kinds of attempt outcomes the service can record. */
    enum Outcome {
        UNKNOWN_USER,
        LOCKED,
        FAILED,
        REJECTED,
        SUCCESS
    }

    /** One generated login attempt: an outcome plus the email and (for credential/other failures) a reason. */
    record Attempt(Outcome outcome, String email, String reason) {
    }

    // Feature: core-platform-completion, Property 24: Every login attempt is logged with its outcome
    @Property(tries = 200)
    void everyAttemptWritesExactlyOneLogEntryRecordingItsOutcome(@ForAll("attempts") List<Attempt> attempts) {
        List<LoginLog> persisted = new ArrayList<>();
        LoginSecurityServiceImpl service = buildService(persisted);

        int expectedCount = 0;

        for (Attempt attempt : attempts) {
            int before = persisted.size();

            invoke(service, attempt);

            // --- exactly one new entry per attempt (Req 11.1.4) ---
            assertThat(persisted)
                    .as("attempt %s writes exactly one new login-log entry", attempt.outcome())
                    .hasSize(before + 1);

            expectedCount++;
            assertThat(persisted)
                    .as("total login-log entries equals total attempts so far")
                    .hasSize(expectedCount);

            LoginLog entry = persisted.get(persisted.size() - 1);

            // The entry identifies the attempt and carries a persistable id.
            assertThat(entry.getId()).as("log entry has an id").isNotNull();
            assertThat(entry.getEmail()).as("log entry records the attempted email").isEqualTo(attempt.email());

            // The entry records the attempt's outcome (success vs failed) and reason.
            if (attempt.outcome() == Outcome.SUCCESS) {
                assertThat(entry.getLoginStatus())
                        .as("successful login is logged with success status")
                        .isEqualTo(STATUS_SUCCESS);
                assertThat(entry.getFailureReason())
                        .as("successful login carries no failure reason")
                        .isNull();
            } else {
                assertThat(entry.getLoginStatus())
                        .as("non-successful attempt %s is logged with failed status", attempt.outcome())
                        .isEqualTo(STATUS_FAILED);
                assertThat(entry.getFailureReason())
                        .as("failed attempt %s carries a non-blank failure reason", attempt.outcome())
                        .isNotBlank();

                // The recorded reason reflects the specific outcome.
                switch (attempt.outcome()) {
                    case UNKNOWN_USER -> assertThat(entry.getFailureReason()).isEqualTo("User not found");
                    case LOCKED -> assertThat(entry.getFailureReason()).startsWith("Account locked until");
                    case FAILED -> assertThat(entry.getFailureReason()).contains(attempt.reason());
                    case REJECTED -> assertThat(entry.getFailureReason()).isEqualTo(attempt.reason());
                    default -> { /* unreachable */ }
                }
            }
        }

        // Final invariant: one persisted entry per attempt, nothing more, nothing less.
        assertThat(persisted)
                .as("number of login-log entries equals number of attempts")
                .hasSize(attempts.size());
    }

    /** Dispatch the generated attempt to the matching record* method on the service. */
    private void invoke(LoginSecurityServiceImpl service, Attempt attempt) {
        switch (attempt.outcome()) {
            case UNKNOWN_USER -> service.recordUnknownUser(attempt.email(), IP, UA);
            case LOCKED -> service.recordLockedAttempt(lockedUser(attempt.email()), attempt.email(), IP, UA);
            case FAILED -> service.recordFailedAttempt(freshUser(attempt.email()), attempt.email(), IP, UA, attempt.reason());
            case REJECTED -> service.recordRejectedAttempt(freshUser(attempt.email()), attempt.email(), IP, UA, attempt.reason());
            case SUCCESS -> service.recordSuccessfulLogin(freshUser(attempt.email()), attempt.email(), IP, UA);
        }
    }

    /** A known account with a clean counter and no lock. */
    private User freshUser(String email) {
        return User.builder()
                .id(UUID.randomUUID())
                .orgId(UUID.randomUUID())
                .email(email)
                .name("Test User")
                .failedLoginCount(0)
                .lockedUntil(null)
                .build();
    }

    /** A known account that is currently locked. */
    private User lockedUser(String email) {
        User u = freshUser(email);
        u.setLockedUntil(LocalDateTime.now().plusMinutes(15));
        return u;
    }

    // --- wiring ---------------------------------------------------------------

    private LoginSecurityServiceImpl buildService(List<LoginLog> persisted) {
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.update(isNull(), any())).thenReturn(1);

        LoginLogMapper loginLogMapper = mock(LoginLogMapper.class);
        when(loginLogMapper.insert(any(LoginLog.class))).thenAnswer(inv -> {
            persisted.add(inv.getArgument(0));
            return 1;
        });

        LoginSecurityServiceImpl service = new LoginSecurityServiceImpl(userMapper, loginLogMapper);
        // @Value-injected fields are not populated outside a Spring context; set sane values
        // so a single failed attempt on a fresh account does not immediately trip the lock
        // suffix, keeping the recorded reason equal to the supplied reason.
        ReflectionTestUtils.setField(service, "maxFailedAttempts", 5);
        ReflectionTestUtils.setField(service, "lockoutDurationMinutes", 15L);
        return service;
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<Attempt>> attempts() {
        Arbitrary<Outcome> outcomes = Arbitraries.of(Outcome.class);
        Arbitrary<String> emails = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12)
                .map(local -> local + "@example.com");
        Arbitrary<String> reasons = Arbitraries.of(
                "Invalid credentials",
                "Account disabled",
                "Password expired",
                "Email not verified");

        Arbitrary<Attempt> attempt = Combinators.combine(outcomes, emails, reasons).as(Attempt::new);
        return attempt.list().ofMinSize(1).ofMaxSize(40);
    }
}
