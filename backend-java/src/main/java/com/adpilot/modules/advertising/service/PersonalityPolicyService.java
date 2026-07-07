package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;

/**
 * Loads the configurable {@code personality_policies} table (the concrete numeric
 * control fields — candidate-eligibility thresholds, approval gating ratios,
 * Safety_Boundary maximum ratios, explore-budget band and V3 keyword fields) for a
 * resolved {@code AI_Personality} and exposes the in-effect {@code rule_version}
 * recorded on every AI Operation (Req 49.5 / 49.6 / 49.9).
 *
 * <p>A policy is resolved with the scope precedence {@code store} override &gt;
 * {@code system} default. The personality is one of the canonical machine values
 * {@code conservative} / {@code balanced} / {@code aggressive}; an unknown or blank
 * personality resolves to the system fallback {@code balanced} (Req 49.2/49.3).</p>
 */
public interface PersonalityPolicyService {

    /** The system fallback personality used when none is resolvable (Req 49.3). */
    String DEFAULT_PERSONALITY = "balanced";

    /**
     * Resolves the effective Personality_Policy for the given personality, preferring
     * a {@code store}-scope override over the {@code system} default. The values are the
     * configurable backend control fields, never hardcoded in the Frontend (Req 49.6).
     *
     * @param personality the resolved AI_Personality machine value; blank/unknown falls
     *                     back to {@code balanced}
     * @return the effective policy row; never {@code null}
     */
    PersonalityPolicyEntity resolvePolicy(String personality);

    /**
     * Exposes the in-effect {@code rule_version} of the Personality_Policy rule-set used
     * for the given personality, to be recorded as the {@code Personality_Rule_Version}
     * on each AI Operation (Req 49.9).
     *
     * @param personality the resolved AI_Personality machine value
     * @return the in-effect rule version string
     */
    String getRuleVersion(String personality);
}
