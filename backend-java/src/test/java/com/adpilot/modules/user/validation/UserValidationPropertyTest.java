package com.adpilot.modules.user.validation;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure {@link UserValidation} helper.
 *
 * <p>Tag: {@code Feature: app-functionality-completion, Property 10: User input
 * validation with case-insensitive email dedup}
 *
 * <p>Property 10: a (name, email) pair is accepted <em>iff</em> the name length is
 * within {@code [1, 255]} and the email is syntactically valid (a single
 * {@code '@'} with a non-empty local part, a non-empty domain part, and a total
 * length of at most 320). Independently, an email that differs from an existing
 * user's email only by letter case is rejected as a duplicate.
 *
 * <p>Validates: Requirements 5.1, 5.3, 5.4.
 *
 * <p>These exercise the pure helper directly — no Spring context is needed because
 * each method deterministically maps its inputs to a boolean decision.
 */
@Label("Feature: app-functionality-completion, Property 10: User input validation with case-insensitive email dedup")
class UserValidationPropertyTest {

    /**
     * Feature: app-functionality-completion, Property 10: User input validation
     * with case-insensitive email dedup.
     *
     * <p>Req 5.1 / 5.3: for any name and email, {@link UserValidation#isValid}
     * accepts the pair if and only if the name is valid (length 1–255) AND the
     * email is syntactically valid. This anchors acceptance to the exact
     * conjunction of the two field rules across the whole input space.
     */
    @Property(tries = 200)
    void acceptedIffNameAndEmailBothValid(
            @ForAll("anyName") String name,
            @ForAll("anyEmail") String email) {

        boolean expected = UserValidation.isValidName(name) && UserValidation.isValidEmail(email);

        assertThat(UserValidation.isValid(name, email)).isEqualTo(expected);
    }

    /**
     * Feature: app-functionality-completion, Property 10: User input validation
     * with case-insensitive email dedup.
     *
     * <p>Req 5.3: a name is valid exactly when its length lies in
     * {@code [1, 255]}. Verified against an independently-computed length check
     * over names spanning empty, in-range, and over-length values.
     */
    @Property(tries = 200)
    void nameValidIffLengthWithinBounds(@ForAll("anyName") String name) {
        int len = name.length();
        boolean expected = len >= UserValidation.NAME_MIN_LENGTH && len <= UserValidation.NAME_MAX_LENGTH;

        assertThat(UserValidation.isValidName(name)).isEqualTo(expected);
    }

    /**
     * Feature: app-functionality-completion, Property 10: User input validation
     * with case-insensitive email dedup.
     *
     * <p>Req 5.1 / 5.3: a structurally well-formed email — one {@code '@'} with a
     * non-empty local part, a non-empty dot-bearing domain, and an overall length
     * within 320 — is always accepted by {@link UserValidation#isValidEmail}.
     */
    @Property(tries = 200)
    void wellFormedEmailIsAccepted(@ForAll("validEmail") String email) {
        assertThat(UserValidation.isValidEmail(email)).isTrue();
    }

    /**
     * Feature: app-functionality-completion, Property 10: User input validation
     * with case-insensitive email dedup.
     *
     * <p>Req 5.3: an email with a number of {@code '@'} characters other than one,
     * an empty local or domain part, or a length over 320 is always rejected.
     */
    @Property(tries = 200)
    void malformedEmailIsRejected(@ForAll("malformedEmail") String email) {
        assertThat(UserValidation.isValidEmail(email)).isFalse();
    }

    /**
     * Feature: app-functionality-completion, Property 10: User input validation
     * with case-insensitive email dedup.
     *
     * <p>Req 5.4: an email that differs from an existing user's email only by
     * letter case is detected as a duplicate. We take any existing email, apply an
     * arbitrary per-character case flip, and assert the dedup check rejects it.
     */
    @Property(tries = 200)
    void caseOnlyVariantOfExistingEmailIsDuplicate(
            @ForAll("validEmail") String existing,
            @ForAll long caseSeed) {

        String caseVariant = flipCase(existing, caseSeed);
        List<String> existingEmails = List.of(existing);

        // Sanity: the variant differs only by case, so it normalizes identically.
        assertThat(UserValidation.normalizeEmail(caseVariant))
                .isEqualTo(UserValidation.normalizeEmail(existing));

        assertThat(UserValidation.isDuplicateEmail(caseVariant, existingEmails)).isTrue();
        assertThat(UserValidation.emailsMatch(caseVariant, existing)).isTrue();
    }

    /**
     * Feature: app-functionality-completion, Property 10: User input validation
     * with case-insensitive email dedup.
     *
     * <p>Req 5.4: an email whose normalized form matches none of the existing
     * users' normalized emails is never reported as a duplicate.
     */
    @Property(tries = 200)
    void nonMatchingEmailIsNotDuplicate(
            @ForAll("validEmail") String candidate,
            @ForAll("emailList") List<String> existing) {

        String normalizedCandidate = UserValidation.normalizeEmail(candidate);
        boolean anyMatch = existing.stream()
                .map(UserValidation::normalizeEmail)
                .anyMatch(normalizedCandidate::equals);

        assertThat(UserValidation.isDuplicateEmail(candidate, existing)).isEqualTo(anyMatch);
    }

    // ---- Helpers ----

    /** Flips the case of letters in {@code value} driven by the bits of {@code seed}. */
    private static String flipCase(String value, long seed) {
        StringBuilder sb = new StringBuilder(value.length());
        long bits = seed;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean flip = (bits & 1L) == 1L;
            bits >>>= 1;
            if (flip && Character.isLetter(c)) {
                sb.append(Character.isUpperCase(c)
                        ? Character.toLowerCase(c)
                        : Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- Generators (constrained intelligently to the input space) ----

    /**
     * Names spanning empty (invalid), in-range (valid), and over-length (invalid)
     * so the length boundary on both sides is exercised.
     */
    @Provide
    Arbitrary<String> anyName() {
        return Arbitraries.strings()
                .withCharRange('\u0020', '\u007E')
                .ofMinLength(0)
                .ofMaxLength(UserValidation.NAME_MAX_LENGTH + 20);
    }

    /**
     * A mix of structurally valid and malformed emails so {@link #acceptedIffNameAndEmailBothValid}
     * and {@link #nameValidIffLengthWithinBounds} see both acceptance and rejection paths.
     */
    @Provide
    Arbitrary<String> anyEmail() {
        return Arbitraries.oneOf(validEmail(), malformedEmail());
    }

    /**
     * Syntactically valid emails: non-empty local part, single {@code '@'}, non-empty
     * domain, total length within {@link UserValidation#EMAIL_MAX_LENGTH}.
     */
    @Provide
    Arbitrary<String> validEmail() {
        Arbitrary<String> local = atomPart().ofMinLength(1).ofMaxLength(64);
        Arbitrary<String> domainLabel = atomPart().ofMinLength(1).ofMaxLength(40);
        Arbitrary<String> tld = Arbitraries.of("com", "net", "org", "io", "co", "dev");
        return Combinators.combine(local, domainLabel, tld)
                .as((l, d, t) -> l + "@" + d + "." + t)
                .filter(e -> e.length() <= UserValidation.EMAIL_MAX_LENGTH);
    }

    /**
     * Emails that violate at least one syntax rule: wrong {@code '@'} count, empty
     * local/domain, or excessive length.
     */
    @Provide
    Arbitrary<String> malformedEmail() {
        Arbitrary<String> noAt = atomPart().ofMinLength(0).ofMaxLength(30);
        Arbitrary<String> emptyLocal = atomPart().ofMinLength(1).ofMaxLength(20)
                .map(d -> "@" + d);
        Arbitrary<String> emptyDomain = atomPart().ofMinLength(1).ofMaxLength(20)
                .map(l -> l + "@");
        Arbitrary<String> doubleAt = Combinators
                .combine(atomPart().ofMinLength(1).ofMaxLength(15),
                         atomPart().ofMinLength(1).ofMaxLength(15),
                         atomPart().ofMinLength(1).ofMaxLength(15))
                .as((a, b, c) -> a + "@" + b + "@" + c);
        Arbitrary<String> tooLong = atomPart().ofMinLength(1).ofMaxLength(10)
                .map(l -> l + "@" + "a".repeat(UserValidation.EMAIL_MAX_LENGTH) + ".com");
        return Arbitraries.oneOf(noAt, emptyLocal, emptyDomain, doubleAt, tooLong);
    }

    /** A list of valid existing emails (0–8 entries) for dedup checks. */
    @Provide
    Arbitrary<List<String>> emailList() {
        return validEmail().list().ofMaxSize(8);
    }

    /** Characters allowed in a generated local/domain atom (letters mix case for dedup). */
    private net.jqwik.api.arbitraries.StringArbitrary atomPart() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
    }
}
