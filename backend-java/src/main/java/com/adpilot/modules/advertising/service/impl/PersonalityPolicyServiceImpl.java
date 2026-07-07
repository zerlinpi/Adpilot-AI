package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.mapper.PersonalityPolicyMapper;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Loads the configurable {@code personality_policies} table with the scope precedence
 * {@code store} override &gt; {@code system} default, falling back to the {@code balanced}
 * system policy when the requested personality is blank or not one of the canonical
 * machine values (Req 49.2/49.3/49.5/49.6/49.9).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalityPolicyServiceImpl implements PersonalityPolicyService {

    private static final String SCOPE_STORE = "store";
    private static final String SCOPE_SYSTEM = "system";

    /** Canonical AI_Personality machine values (Req 49.1). */
    private static final Set<String> CANONICAL_PERSONALITIES =
            Set.of("conservative", "balanced", "aggressive");

    private final PersonalityPolicyMapper personalityPolicyMapper;

    @Override
    public PersonalityPolicyEntity resolvePolicy(String personality) {
        String resolved = normalize(personality);

        // Scope precedence: a store-scope override wins over the system default.
        PersonalityPolicyEntity policy = findPolicy(SCOPE_STORE, resolved);
        if (policy == null) {
            policy = findPolicy(SCOPE_SYSTEM, resolved);
        }

        // If a non-default personality has no row at all, fall back to the balanced system policy.
        if (policy == null && !DEFAULT_PERSONALITY.equals(resolved)) {
            log.warn("No personality_policies row for personality={}, falling back to {}",
                    resolved, DEFAULT_PERSONALITY);
            policy = findPolicy(SCOPE_SYSTEM, DEFAULT_PERSONALITY);
        }

        if (policy == null) {
            throw new BusinessException(
                    "PERSONALITY_POLICY_NOT_FOUND",
                    "No Personality_Policy configured for personality '" + resolved
                            + "' and no system '" + DEFAULT_PERSONALITY + "' fallback exists");
        }
        return policy;
    }

    @Override
    public String getRuleVersion(String personality) {
        return resolvePolicy(personality).getRuleVersion();
    }

    /** Normalizes the personality to a canonical machine value, defaulting to {@code balanced}. */
    private String normalize(String personality) {
        if (personality == null || personality.isBlank()) {
            return DEFAULT_PERSONALITY;
        }
        String trimmed = personality.trim().toLowerCase(Locale.ROOT);
        return CANONICAL_PERSONALITIES.contains(trimmed) ? trimmed : DEFAULT_PERSONALITY;
    }

    private PersonalityPolicyEntity findPolicy(String scope, String personality) {
        LambdaQueryWrapper<PersonalityPolicyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PersonalityPolicyEntity::getScope, scope)
                .eq(PersonalityPolicyEntity::getPersonality, personality)
                .last("LIMIT 1");
        return personalityPolicyMapper.selectOne(wrapper);
    }
}
