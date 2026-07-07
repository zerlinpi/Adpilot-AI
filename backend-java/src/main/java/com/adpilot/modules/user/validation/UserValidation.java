package com.adpilot.modules.user.validation;

import java.util.Collection;
import java.util.Locale;

/**
 * Pure, side-effect-free validation helper for User creation (Req 5.1, 5.3, 5.4).
 *
 * <p>This class is intentionally dependency-free so it can be exercised directly by
 * property-based tests (jqwik) and reused by the service layer. The rules encode the
 * acceptance criteria of Requirement 5:</p>
 *
 * <ul>
 *   <li><b>Name</b> — must be 1 to 255 characters (Req 5.1, 5.3).</li>
 *   <li><b>Email</b> — must contain a single {@code '@'} with a non-empty local part and a
 *       non-empty domain part, and be at most 320 characters total (Req 5.1, 5.3).</li>
 *   <li><b>Dedup</b> — two emails are considered the same when they match
 *       case-insensitively (Req 5.4).</li>
 * </ul>
 */
public final class UserValidation {

    /** Minimum allowed user name length (inclusive). */
    public static final int NAME_MIN_LENGTH = 1;

    /** Maximum allowed user name length (inclusive). */
    public static final int NAME_MAX_LENGTH = 255;

    /** Maximum allowed email length (inclusive). */
    public static final int EMAIL_MAX_LENGTH = 320;

    private UserValidation() {
        // Utility class — no instances.
    }

    /**
     * A name is valid when it is non-null and its length is within
     * {@code [NAME_MIN_LENGTH, NAME_MAX_LENGTH]} (Req 5.1, 5.3).
     */
    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        int len = name.length();
        return len >= NAME_MIN_LENGTH && len <= NAME_MAX_LENGTH;
    }

    /**
     * An email is valid when it is non-null, at most {@link #EMAIL_MAX_LENGTH} characters,
     * contains exactly one {@code '@'}, and has a non-empty local part and a non-empty
     * domain part (Req 5.1, 5.3).
     */
    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        if (email.isEmpty() || email.length() > EMAIL_MAX_LENGTH) {
            return false;
        }
        int firstAt = email.indexOf('@');
        int lastAt = email.lastIndexOf('@');
        // Must contain exactly one '@'.
        if (firstAt < 0 || firstAt != lastAt) {
            return false;
        }
        String local = email.substring(0, firstAt);
        String domain = email.substring(firstAt + 1);
        return !local.isEmpty() && !domain.isEmpty();
    }

    /**
     * Convenience predicate combining {@link #isValidName} and {@link #isValidEmail}.
     */
    public static boolean isValid(String name, String email) {
        return isValidName(name) && isValidEmail(email);
    }

    /**
     * Normalize an email for case-insensitive comparison/dedup (Req 5.4): lower-cased
     * using {@link Locale#ROOT}. Returns {@code null} for a {@code null} input.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns {@code true} when {@code a} and {@code b} represent the same email under a
     * case-insensitive comparison (Req 5.4). Two {@code null} inputs do not match.
     */
    public static boolean emailsMatch(String a, String b) {
        String na = normalizeEmail(a);
        String nb = normalizeEmail(b);
        return na != null && na.equals(nb);
    }

    /**
     * Returns {@code true} when {@code candidate} matches any email in
     * {@code existingEmails} case-insensitively (Req 5.4). Used to reject creating a user
     * whose email differs from an existing user's only by case.
     */
    public static boolean isDuplicateEmail(String candidate, Collection<String> existingEmails) {
        String normalized = normalizeEmail(candidate);
        if (normalized == null || existingEmails == null) {
            return false;
        }
        return existingEmails.stream()
                .map(UserValidation::normalizeEmail)
                .anyMatch(normalized::equals);
    }
}
