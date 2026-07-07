package com.adpilot.modules.advertising.support;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Reusable, in-memory duplicate-suppression helper for recommendation generation
 * (Requirement 18.5) and smart diagnosis (Requirement 46.3/46.4, task 14.6).
 *
 * <p>A "duplicate" is defined as <strong>the same change for the same target</strong>: two
 * suggestions that target the same advertising object (identified by its entity type and id) and
 * propose the same change type are considered the same Recommendation. This class collapses such
 * collisions into a single emission regardless of how many rules or passes propose it.</p>
 *
 * <p>The component is deliberately <strong>pure and side-effect free</strong> (no persistence, no
 * framework): a caller constructs one instance per generation/diagnosis pass, optionally
 * {@link #seed(String) seeds} it with the keys of already-existing Recommendations so a new pass does
 * not recreate them, and then calls {@link #tryEmit(String, Object, String)} before producing each
 * Recommendation. The first call for a given key returns {@code true} (emit it); every subsequent
 * call for the same key returns {@code false} (suppress it). This makes the suppression logic
 * trivially unit- and property-testable and lets {@code RecommendationEngineService} and
 * {@code SmartDiagnosisService} share exactly one implementation.</p>
 */
public final class RecommendationDeduplicator {

    private final Set<String> emittedKeys = new HashSet<>();

    /**
     * Build the canonical duplicate-suppression key for a change against a target. Two suggestions
     * with the same {@code (targetEntityType, targetEntityId, changeType)} produce an equal key and
     * are treated as the same Recommendation. Null components are normalized so a missing value never
     * raises a null-reference error.
     *
     * @param targetEntityType the target object kind (for example {@code keyword}/{@code campaign}/{@code target})
     * @param targetEntityId   the target object identifier (for example a {@code UUID}); may be {@code null}
     * @param changeType       the proposed change type (for example {@code decrease_bid}/{@code add_negative})
     * @return the canonical, stable key; never {@code null}
     */
    public static String keyOf(String targetEntityType, Object targetEntityId, String changeType) {
        return norm(targetEntityType) + "::"
                + (targetEntityId == null ? "" : targetEntityId.toString().trim().toLowerCase())
                + "::" + norm(changeType);
    }

    /**
     * Seed the deduplicator with an already-known key (for example one derived from an existing,
     * not-yet-resolved Recommendation) so a fresh pass does not recreate it.
     *
     * @param key a key produced by {@link #keyOf(String, Object, String)}; {@code null} is ignored
     */
    public void seed(String key) {
        if (key != null) {
            emittedKeys.add(key);
        }
    }

    /**
     * Seed the deduplicator with a batch of already-known keys.
     *
     * @param keys keys produced by {@link #keyOf(String, Object, String)}; {@code null} is ignored
     */
    public void seedAll(Collection<String> keys) {
        if (keys != null) {
            for (String key : keys) {
                seed(key);
            }
        }
    }

    /**
     * Attempt to emit a change for a target, registering it as emitted in the process.
     *
     * @param targetEntityType the target object kind
     * @param targetEntityId   the target object identifier; may be {@code null}
     * @param changeType       the proposed change type
     * @return {@code true} if this change has not been emitted before (the caller SHOULD emit it);
     *         {@code false} if it is a duplicate (the caller MUST suppress it)
     */
    public boolean tryEmit(String targetEntityType, Object targetEntityId, String changeType) {
        return tryEmit(keyOf(targetEntityType, targetEntityId, changeType));
    }

    /**
     * Attempt to emit a pre-built key, registering it as emitted in the process.
     *
     * @param key a key produced by {@link #keyOf(String, Object, String)}
     * @return {@code true} if newly registered (emit), {@code false} if already present (suppress)
     */
    public boolean tryEmit(String key) {
        return emittedKeys.add(key);
    }

    /**
     * Report whether a change for a target would be a duplicate without registering it.
     *
     * @return {@code true} if the key is already registered
     */
    public boolean isDuplicate(String targetEntityType, Object targetEntityId, String changeType) {
        return emittedKeys.contains(keyOf(targetEntityType, targetEntityId, changeType));
    }

    /** @return the number of distinct keys registered so far. */
    public int size() {
        return emittedKeys.size();
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
