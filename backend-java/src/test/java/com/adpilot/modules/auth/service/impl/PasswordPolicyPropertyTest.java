package com.adpilot.modules.auth.service.impl;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link PasswordPolicyServiceImpl}, the configurable
 * password-strength policy (task 22.1).
 *
 * Feature: core-platform-completion, Property 23: Password acceptance matches
 * the strength policy.
 *
 * For any candidate password and any configured policy, the service accepts the
 * password if and only if it satisfies every configured strength rule (length
 * bounds and character-class requirements). The test sets the policy fields via
 * reflection to known values and compares {@code isValid} against an
 * independent reference check.
 *
 * Validates: Requirements 11.1.3
 */
class PasswordPolicyPropertyTest {

    /** Mirrors the {@code @Value}-injected policy fields under test. */
    private static final class PolicyConfig {
        final int minLength;
        final int maxLength;
        final boolean requireUppercase;
        final boolean requireLowercase;
        final boolean requireDigit;
        final boolean requireSpecial;

        PolicyConfig(int minLength, int maxLength, boolean requireUppercase,
                     boolean requireLowercase, boolean requireDigit, boolean requireSpecial) {
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.requireUppercase = requireUppercase;
            this.requireLowercase = requireLowercase;
            this.requireDigit = requireDigit;
            this.requireSpecial = requireSpecial;
        }

        @Override
        public String toString() {
            return "Policy[min=" + minLength + ", max=" + maxLength
                    + ", upper=" + requireUppercase + ", lower=" + requireLowercase
                    + ", digit=" + requireDigit + ", special=" + requireSpecial + "]";
        }
    }

    // Feature: core-platform-completion, Property 23: Password acceptance matches the strength policy
    // Req 11.1.3: a password is accepted iff it satisfies every configured strength rule.
    @Property(tries = 300)
    void acceptanceMatchesConfiguredPolicy(
            @ForAll("policies") PolicyConfig policy,
            @ForAll("passwords") String password) {

        PasswordPolicyServiceImpl service = serviceWith(policy);

        boolean expected = satisfiesPolicy(password, policy);
        boolean actual = service.isValid(password);

        assertThat(actual)
                .as("isValid('%s') under %s should be %s", password, policy, expected)
                .isEqualTo(expected);

        // validateAndCollect is the source of truth for isValid: empty violations <=> valid.
        assertThat(service.validateAndCollect(password).isEmpty())
                .as("validateAndCollect emptiness must agree with isValid for '%s'", password)
                .isEqualTo(actual);
    }

    /**
     * Independent reference implementation of the strength policy. A password is
     * accepted only when it is non-empty, within the configured length bounds,
     * and contains a character from every required character class.
     */
    private static boolean satisfiesPolicy(String password, PolicyConfig policy) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        if (password.length() < policy.minLength) {
            return false;
        }
        if (password.length() > policy.maxLength) {
            return false;
        }
        if (policy.requireUppercase && !hasMatch(password, Character::isUpperCase)) {
            return false;
        }
        if (policy.requireLowercase && !hasMatch(password, Character::isLowerCase)) {
            return false;
        }
        if (policy.requireDigit && !hasMatch(password, Character::isDigit)) {
            return false;
        }
        if (policy.requireSpecial && !hasMatch(password, c -> !Character.isLetterOrDigit(c))) {
            return false;
        }
        return true;
    }

    private interface CharPredicate {
        boolean test(char c);
    }

    private static boolean hasMatch(String s, CharPredicate predicate) {
        for (int i = 0; i < s.length(); i++) {
            if (predicate.test(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static PasswordPolicyServiceImpl serviceWith(PolicyConfig policy) {
        PasswordPolicyServiceImpl service = new PasswordPolicyServiceImpl();
        ReflectionTestUtils.setField(service, "minLength", policy.minLength);
        ReflectionTestUtils.setField(service, "maxLength", policy.maxLength);
        ReflectionTestUtils.setField(service, "requireUppercase", policy.requireUppercase);
        ReflectionTestUtils.setField(service, "requireLowercase", policy.requireLowercase);
        ReflectionTestUtils.setField(service, "requireDigit", policy.requireDigit);
        ReflectionTestUtils.setField(service, "requireSpecial", policy.requireSpecial);
        return service;
    }

    // --- generators -----------------------------------------------------------

    /**
     * A spread of policy configurations: the production default plus randomized
     * length bounds (with min <= max) and independently toggled character-class
     * requirements, so every rule is exercised both on and off.
     */
    @Provide
    Arbitrary<PolicyConfig> policies() {
        Arbitrary<PolicyConfig> defaultPolicy =
                Arbitraries.just(new PolicyConfig(8, 128, true, true, true, false));

        Arbitrary<PolicyConfig> randomPolicy = Combinators.combine(
                        Arbitraries.integers().between(1, 12),
                        Arbitraries.integers().between(0, 12),
                        Arbitraries.of(true, false),
                        Arbitraries.of(true, false),
                        Arbitraries.of(true, false),
                        Arbitraries.of(true, false))
                .as((a, b, upper, lower, digit, special) -> {
                    int min = Math.min(a, b == 0 ? a : b);
                    int max = Math.max(a, b == 0 ? a : b);
                    return new PolicyConfig(min, max, upper, lower, digit, special);
                });

        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(1, defaultPolicy),
                net.jqwik.api.Tuple.of(4, randomPolicy));
    }

    /**
     * Passwords drawn from a mixed alphabet of uppercase, lowercase, digits and
     * special characters across a range of lengths, including the empty string,
     * so generated values both satisfy and violate each rule. Lengths exceed the
     * default max-length window to exercise the upper bound as well.
     */
    @Provide
    Arbitrary<String> passwords() {
        return Arbitraries.strings()
                .withChars("abcdefghABCDEFGH0123456789!@#$%^&*_-".toCharArray())
                .ofMinLength(0)
                .ofMaxLength(140);
    }
}
