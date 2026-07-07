package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.support.ObjectStatusVocabulary;
import com.adpilot.modules.advertising.support.OptimizationGoalVocabulary;

/**
 * The single, authoritative boundary that guarantees every advertising response object emits the four
 * enumerated fields — AI_Hosting_Status, AI_Personality, Optimization_Goal, and Object_Status — as
 * {@code Machine_Value_Enum} values only, and <strong>never</strong> as a Chinese display string
 * (Requirements 14.7, 48.4; Property 34). Display translation is the Frontend's responsibility
 * (task 21.2), so the Backend contract carries only the stable, language-neutral machine values.
 *
 * <p>This is a <strong>pure</strong>, stateless utility used by the {@code *Converter} classes when
 * projecting an entity onto its VO. Each method delegates to the one canonical vocabulary that owns
 * the enum:</p>
 * <ul>
 *   <li>{@link ObjectStatusVocabulary} for Object_Status ({@code enabled|paused|archived}),</li>
 *   <li>{@link OptimizationGoalVocabulary} for Optimization_Goal
 *       ({@code profit_first|sales_growth|rank|clearance}), and</li>
 *   <li>{@link AiPersonality} for AI_Personality ({@code conservative|balanced|aggressive}).</li>
 * </ul>
 *
 * <p><strong>Never guesses, never leaks a display string.</strong> A recognized canonical or legacy
 * value is normalized to its canonical machine value; an unrecognized value (including any Chinese
 * display string or otherwise non-canonical value) is mapped to {@code null} rather than passed
 * through, so a non-machine value can never reach the API contract. Persisted values are normalized
 * by the migration tasks (14.7/14.8/14.9); this emitter is the defensive read-time boundary that
 * keeps the contract honest regardless of what is stored.</p>
 *
 * <p>Validates: Requirements 14.7, 48.4.</p>
 */
public final class AdvertisingEnumEmitter {

    /** AI_Hosting_Status machine value when a Campaign is under AI hosting (Req 48.4). */
    public static final String HOSTED = "hosted";

    /** AI_Hosting_Status machine value when a Campaign is not under AI hosting (Req 48.4). */
    public static final String NOT_HOSTED = "not_hosted";

    private AdvertisingEnumEmitter() {
        // Utility class — not instantiable.
    }

    /**
     * Emit the AI_Hosting_Status machine value for a Campaign (Req 48.4). This is a total function of
     * the {@code hosting_enabled} flag and never returns a display string.
     *
     * @param hostingEnabled whether the Campaign is under AI hosting
     * @return {@link #HOSTED} when hosting is enabled, otherwise {@link #NOT_HOSTED}
     */
    public static String aiHostingStatus(boolean hostingEnabled) {
        return hostingEnabled ? HOSTED : NOT_HOSTED;
    }

    /**
     * Emit the Object_Status machine value ({@code enabled|paused|archived}) for a raw stored status,
     * normalizing recognized legacy values (e.g. {@code active → enabled}) and returning {@code null}
     * for an absent or unrecognized value rather than leaking a non-machine value (Req 16.1, 48.4).
     *
     * @param raw the raw stored status (may be {@code null})
     * @return the canonical Object_Status machine value, or {@code null} when absent/unrecognized
     */
    public static String objectStatus(String raw) {
        return ObjectStatusVocabulary.toCanonical(raw).orElse(null);
    }

    /**
     * Emit the Optimization_Goal machine value ({@code profit_first|sales_growth|rank|clearance}) for
     * a raw stored goal-type / hosting-goal value, normalizing recognized legacy values (e.g.
     * {@code maximize_sales_at_target → sales_growth}) and returning {@code null} for an absent or
     * unmappable value rather than leaking a non-machine value (Req 57.1, 48.4).
     *
     * @param raw the raw stored value (may be {@code null})
     * @return the canonical Optimization_Goal machine value, or {@code null} when absent/unmappable
     */
    public static String optimizationGoal(String raw) {
        return OptimizationGoalVocabulary.toCanonical(raw).orElse(null);
    }

    /**
     * Emit the AI_Personality machine value ({@code conservative|balanced|aggressive}) for a raw
     * stored personality, returning {@code null} for an absent or non-canonical value rather than
     * leaking a non-machine value such as the retired alternate name or a display string
     * (Req 49.1, 48.4).
     *
     * @param raw the raw stored personality (may be {@code null})
     * @return the canonical AI_Personality machine value, or {@code null} when absent/unrecognized
     */
    public static String aiPersonality(String raw) {
        return AiPersonality.parse(raw).map(AiPersonality::machineValue).orElse(null);
    }
}
