package com.adpilot.modules.auth.service.impl;

import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link LoginSecurityServiceImpl}'s lockout counter
 * (task 22.2).
 *
 * Feature: core-platform-completion, Property 22: Login lockout counter is
 * threshold-correct, resets on success, and is per-account.
 *
 * <p>For any sequence of login attempts spread across multiple accounts, the
 * per-account consecutive failed-attempt counter increments on each failure,
 * the account locks exactly when the counter reaches the configured threshold,
 * a successful login resets the counter to zero and clears the lock, and the
 * counter/lock of one account never influences another.</p>
 *
 * <p>{@link UserMapper} is replaced with a stateful Mockito stub: every
 * {@code update(null, LambdaUpdateWrapper)} is parsed and applied to an
 * in-memory per-user backing store keyed by the row id, so the persisted
 * {@code failed_login_count}/{@code locked_until} values can be observed
 * end-to-end. {@link LoginLogMapper} is a plain stub (logging must never affect
 * the counter). The lockout threshold and duration are injected via reflection
 * into the {@code @Value} fields. Expected behaviour is computed by an
 * independent per-account reference model and compared after every attempt.</p>
 *
 * Validates: Requirements 11.1.1, 11.1.5, 11.1.6
 */
class LoginLockoutCounterPropertyTest {

    private static final String IP = "203.0.113.7";
    private static final String UA = "JUnit/jqwik";

    static {
        // LambdaUpdateWrapper needs the User table/column metadata to resolve
        // method-reference columns (e.g. User::getFailedLoginCount). Initialise it
        // once so the service can build its update wrappers without a Spring/MyBatis
        // context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    // Feature: core-platform-completion, Property 22: Login lockout counter is threshold-correct, resets on success, and is per-account
    // Req 11.1.1: counter increments per failed attempt and locks exactly at the configured threshold.
    // Req 11.1.5: a successful login resets the counter to zero and clears the lock.
    // Req 11.1.6: the counter and lock are isolated per account.
    @Property(tries = 200)
    void lockoutCounterIsThresholdCorrectResetsOnSuccessAndIsPerAccount(
            @ForAll("attemptSequences") List<List<Boolean>> sequences,
            @ForAll @IntRange(min = 2, max = 8) int threshold,
            @ForAll @LongRange(min = 5L, max = 60L) long durationMinutes) {

        Fixture fixture = new Fixture(threshold, durationMinutes);

        int accountCount = sequences.size();
        // One live User row per account (the service mutates these in place, just
        // as a freshly loaded row would carry the persisted state on the next call).
        List<User> users = new ArrayList<>(accountCount);
        for (int a = 0; a < accountCount; a++) {
            users.add(fixture.newAccount("user" + a + "@example.com"));
        }

        // Independent reference model. Locks set during the test never expire
        // (duration >= 5 min, test runs in milliseconds), so a locked account
        // stays locked and its counter is frozen until a (rejected) future op.
        int[] refCount = new int[accountCount];
        boolean[] refLocked = new boolean[accountCount];

        int maxLen = sequences.stream().mapToInt(List::size).max().orElse(0);

        // Interleave attempts round-robin across accounts to exercise isolation:
        // an op on one account must never perturb another account's state.
        for (int step = 0; step < maxLen; step++) {
            for (int a = 0; a < accountCount; a++) {
                List<Boolean> ops = sequences.get(a);
                if (step >= ops.size()) {
                    continue;
                }
                boolean isFailure = ops.get(step);
                User user = users.get(a);
                String email = user.getEmail();

                // Mirror the real authentication flow: a locked account is rejected
                // outright (no counter change) regardless of credential correctness.
                if (fixture.service.isLocked(user)) {
                    fixture.service.recordLockedAttempt(user, email, IP, UA);
                    // reference: unchanged
                } else if (isFailure) {
                    fixture.service.recordFailedAttempt(user, email, IP, UA, "bad credentials");
                    refCount[a] = refCount[a] + 1;
                    if (refCount[a] >= threshold) {
                        refLocked[a] = true;
                    }
                } else {
                    fixture.service.recordSuccessfulLogin(user, email, IP, UA);
                    refCount[a] = 0;
                    refLocked[a] = false;
                }

                // The just-touched account must match its running reference both in
                // the persisted store (via the update wrapper) and in the live row.
                assertAccountState(fixture, user, refCount[a], refLocked[a], threshold, a, step);
            }
        }

        // Per-account isolation: after the full interleaved run, every account must
        // independently match its own reference model.
        for (int a = 0; a < accountCount; a++) {
            assertAccountState(fixture, users.get(a), refCount[a], refLocked[a], threshold, a, -1);
        }
    }

    private void assertAccountState(Fixture fixture, User user, int expectedCount,
                                    boolean expectedLocked, int threshold, int account, int step) {
        PersistedRow persisted = fixture.persisted.get(user.getId());
        String where = " (account=" + account + ", step=" + step + ", threshold=" + threshold + ")";

        // Persisted counter (written through the LambdaUpdateWrapper) tracks the
        // consecutive failure count exactly.
        assertThat(persisted.failedLoginCount)
                .as("persisted failed_login_count must equal the reference counter" + where)
                .isEqualTo(expectedCount);

        // Live row mutated in place agrees with the persisted store.
        assertThat(user.getFailedLoginCount())
                .as("in-place failed_login_count must equal the reference counter" + where)
                .isEqualTo(expectedCount);

        boolean persistedLocked =
                persisted.lockedUntil != null && persisted.lockedUntil.isAfter(LocalDateTime.now());
        assertThat(persistedLocked)
                .as("persisted lock state must match the reference" + where)
                .isEqualTo(expectedLocked);

        // The service's own lock view must agree with the reference.
        assertThat(fixture.service.isLocked(user))
                .as("isLocked must match the reference" + where)
                .isEqualTo(expectedLocked);

        // Locking happens exactly at the threshold: never locked below it, and the
        // counter is exactly the threshold once locked.
        if (expectedLocked) {
            assertThat(expectedCount)
                    .as("a locked account's counter must equal the threshold" + where)
                    .isEqualTo(threshold);
        } else {
            assertThat(expectedCount)
                    .as("an unlocked account's counter must stay below the threshold" + where)
                    .isLessThan(threshold);
        }
    }

    /**
     * Service under test wired to a stateful {@link UserMapper} stub whose
     * {@code update} applies the {@link LambdaUpdateWrapper} to a per-row backing
     * store, plus a no-op {@link LoginLogMapper}.
     */
    private static final class Fixture {
        final Map<UUID, PersistedRow> persisted = new HashMap<>();
        final UserMapper userMapper = mock(UserMapper.class);
        final LoginLogMapper loginLogMapper = mock(LoginLogMapper.class);
        final LoginSecurityServiceImpl service;

        Fixture(int threshold, long durationMinutes) {
            when(userMapper.update(any(), any())).thenAnswer(inv -> {
                Object wrapper = inv.getArgument(1);
                applyUpdate((LambdaUpdateWrapper<User>) wrapper);
                return 1;
            });
            // The service re-reads the row after the atomic increment to drive the
            // lock decision; return a User projection of the persisted backing row.
            when(userMapper.selectById(any())).thenAnswer(inv -> {
                UUID id = inv.getArgument(0) instanceof UUID
                        ? (UUID) inv.getArgument(0)
                        : UUID.fromString(String.valueOf(inv.getArgument(0)));
                PersistedRow row = persisted.get(id);
                if (row == null) {
                    return null;
                }
                return User.builder()
                        .id(id)
                        .failedLoginCount(row.failedLoginCount)
                        .lockedUntil(row.lockedUntil)
                        .build();
            });

            service = new LoginSecurityServiceImpl(userMapper, loginLogMapper);
            ReflectionTestUtils.setField(service, "maxFailedAttempts", threshold);
            ReflectionTestUtils.setField(service, "lockoutDurationMinutes", durationMinutes);
        }

        User newAccount(String email) {
            UUID id = UUID.randomUUID();
            User user = User.builder()
                    .id(id)
                    .orgId(UUID.randomUUID())
                    .email(email)
                    .name(email)
                    .failedLoginCount(0)
                    .lockedUntil(null)
                    .build();
            persisted.put(id, new PersistedRow());
            return user;
        }

        /**
         * Apply a MyBatis-Plus LambdaUpdateWrapper to the in-memory backing store:
         * find the targeted row via the {@code id} equality in the WHERE clause and
         * overwrite the columns named in the SET clause with the bound parameter
         * values (including nulls, e.g. clearing the lock).
         */
        private void applyUpdate(LambdaUpdateWrapper<User> wrapper) {
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            String sqlSet = wrapper.getSqlSet();
            Map<String, Object> setValues = parseClause(sqlSet, params);
            Map<String, Object> whereValues = parseClause(wrapper.getSqlSegment(), params);

            Object idValue = whereValues.get("id");
            UUID id = idValue instanceof UUID ? (UUID) idValue : UUID.fromString(String.valueOf(idValue));
            PersistedRow row = persisted.get(id);
            if (row == null) {
                return;
            }
            // Atomic increment / reset issued via setSql (raw SQL, no bound params):
            // "failed_login_count = failed_login_count + 1" or "failed_login_count = 1".
            if (sqlSet != null) {
                if (sqlSet.contains("failed_login_count = failed_login_count + 1")) {
                    row.failedLoginCount = (row.failedLoginCount == null ? 0 : row.failedLoginCount) + 1;
                } else if (sqlSet.contains("failed_login_count = 1")) {
                    row.failedLoginCount = 1;
                }
            }
            if (setValues.containsKey("failedlogincount")) {
                Object v = setValues.get("failedlogincount");
                row.failedLoginCount = v == null ? 0 : ((Number) v).intValue();
            }
            if (setValues.containsKey("lockeduntil")) {
                row.lockedUntil = (LocalDateTime) setValues.get("lockeduntil");
            }
            // last_login_at is irrelevant to the lockout counter; ignored.
        }

        private static final Pattern PARAM_PATTERN =
                Pattern.compile("([\\w`]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");

        /**
         * Parse a {@code column=#{ew.paramNameValuePairs.KEY}} clause into a map of
         * normalised column name (lower-cased, underscores/backticks stripped) to
         * the bound parameter value.
         */
        private static Map<String, Object> parseClause(String clause, Map<String, Object> params) {
            Map<String, Object> result = new HashMap<>();
            if (clause == null) {
                return result;
            }
            Matcher m = PARAM_PATTERN.matcher(clause);
            while (m.find()) {
                String column = m.group(1).replace("`", "").replace("_", "").toLowerCase();
                String key = m.group(2);
                result.put(column, params.get(key));
            }
            return result;
        }
    }

    /** Minimal persisted projection of the lockout-relevant columns. */
    private static final class PersistedRow {
        Integer failedLoginCount = 0;
        LocalDateTime lockedUntil = null;
    }

    // --- generators -----------------------------------------------------------

    /**
     * One attempt sequence per account (1..5 accounts), each a list (0..25) of
     * booleans where {@code true} is a failed attempt and {@code false} a
     * successful one. The mix produces runs that cross the lock threshold, then
     * recover via success, across several independent accounts.
     */
    @Provide
    Arbitrary<List<List<Boolean>>> attemptSequences() {
        Arbitrary<List<Boolean>> accountOps = Arbitraries.of(true, false)
                .list().ofMinSize(0).ofMaxSize(25);
        return accountOps.list().ofMinSize(1).ofMaxSize(5);
    }
}
