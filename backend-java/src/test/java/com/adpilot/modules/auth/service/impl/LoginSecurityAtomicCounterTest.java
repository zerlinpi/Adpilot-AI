package com.adpilot.modules.auth.service.impl;

import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for the atomic failed-login counter in
 * {@link LoginSecurityServiceImpl#recordFailedAttempt}.
 *
 * <p>The counter is now incremented with a DB-level {@code setSql(
 * "failed_login_count = failed_login_count + 1")} expression instead of writing
 * an absolute value, so concurrent failed logins cannot clobber each other and
 * undercount the run. This test exercises the single-attempt contract: each call
 * increments the persisted counter by exactly one, and crossing the configured
 * threshold sets {@code locked_until}.</p>
 *
 * <p>The {@link UserMapper} is a stateful stub whose {@code update} interprets
 * both the raw {@code setSql} increment and the {@code locked_until} set clause,
 * and whose {@code selectById} returns the current persisted row (the service
 * re-reads the post-increment value to drive the lock decision).</p>
 */
class LoginSecurityAtomicCounterTest {

    static {
        // LambdaUpdateWrapper needs the User table/column metadata to resolve
        // method-reference columns without a Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    private static final Pattern PARAM_PATTERN =
            Pattern.compile("([\\w`]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");

    @Test
    @DisplayName("two sequential failed attempts increment the persisted counter by exactly 2")
    void twoSequentialFailuresIncrementByTwo() {
        Fixture fixture = new Fixture(5, 15L);
        User user = fixture.newAccount("brute@example.com");

        fixture.service.recordFailedAttempt(user, user.getEmail(), "203.0.113.7", "JUnit", "bad password");
        assertThat(fixture.row.failedLoginCount).isEqualTo(1);
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        assertThat(fixture.row.lockedUntil).as("not locked before the threshold").isNull();

        fixture.service.recordFailedAttempt(user, user.getEmail(), "203.0.113.7", "JUnit", "bad password");
        assertThat(fixture.row.failedLoginCount)
                .as("two atomic increments raise the counter by exactly two")
                .isEqualTo(2);
        assertThat(user.getFailedLoginCount()).isEqualTo(2);
        assertThat(fixture.row.lockedUntil).as("still below the threshold, so not locked").isNull();
    }

    @Test
    @DisplayName("crossing the threshold sets locked_until")
    void crossingThresholdLocksAccount() {
        int threshold = 3;
        Fixture fixture = new Fixture(threshold, 15L);
        User user = fixture.newAccount("lock@example.com");

        for (int i = 0; i < threshold - 1; i++) {
            fixture.service.recordFailedAttempt(user, user.getEmail(), "203.0.113.7", "JUnit", "bad password");
            assertThat(fixture.row.lockedUntil).as("no lock before the threshold").isNull();
        }

        fixture.service.recordFailedAttempt(user, user.getEmail(), "203.0.113.7", "JUnit", "bad password");

        assertThat(fixture.row.failedLoginCount).isEqualTo(threshold);
        assertThat(fixture.row.lockedUntil)
                .as("reaching the threshold locks the account")
                .isNotNull()
                .isAfter(LocalDateTime.now());
        assertThat(fixture.service.isLocked(user)).isTrue();
    }

    // --- wiring ---------------------------------------------------------------

    private static final class Row {
        Integer failedLoginCount = 0;
        LocalDateTime lockedUntil = null;
    }

    private final class Fixture {
        final Row row = new Row();
        UUID accountId;
        final UserMapper userMapper = mock(UserMapper.class);
        final LoginLogMapper loginLogMapper = mock(LoginLogMapper.class);
        final LoginSecurityServiceImpl service;

        Fixture(int threshold, long durationMinutes) {
            when(userMapper.update(any(), any())).thenAnswer(inv -> {
                applyUpdate((LambdaUpdateWrapper<User>) inv.getArgument(1));
                return 1;
            });
            when(userMapper.selectById(any())).thenAnswer(inv -> User.builder()
                    .id(accountId)
                    .failedLoginCount(row.failedLoginCount)
                    .lockedUntil(row.lockedUntil)
                    .build());

            service = new LoginSecurityServiceImpl(userMapper, loginLogMapper);
            ReflectionTestUtils.setField(service, "maxFailedAttempts", threshold);
            ReflectionTestUtils.setField(service, "lockoutDurationMinutes", durationMinutes);
        }

        User newAccount(String email) {
            accountId = UUID.randomUUID();
            return User.builder()
                    .id(accountId)
                    .orgId(UUID.randomUUID())
                    .email(email)
                    .name(email)
                    .failedLoginCount(0)
                    .lockedUntil(null)
                    .build();
        }

        private void applyUpdate(LambdaUpdateWrapper<User> wrapper) {
            String sqlSet = wrapper.getSqlSet();
            if (sqlSet != null) {
                if (sqlSet.contains("failed_login_count = failed_login_count + 1")) {
                    row.failedLoginCount = (row.failedLoginCount == null ? 0 : row.failedLoginCount) + 1;
                } else if (sqlSet.contains("failed_login_count = 1")) {
                    row.failedLoginCount = 1;
                }
            }
            Map<String, Object> set = parseClause(sqlSet, wrapper.getParamNameValuePairs());
            if (set.containsKey("lockeduntil")) {
                row.lockedUntil = (LocalDateTime) set.get("lockeduntil");
            }
        }

        private Map<String, Object> parseClause(String clause, Map<String, Object> params) {
            Map<String, Object> result = new HashMap<>();
            if (clause == null) {
                return result;
            }
            Matcher m = PARAM_PATTERN.matcher(clause);
            while (m.find()) {
                String column = m.group(1).replace("`", "").replace("_", "").toLowerCase();
                result.put(column, params.get(m.group(2)));
            }
            return result;
        }
    }
}
