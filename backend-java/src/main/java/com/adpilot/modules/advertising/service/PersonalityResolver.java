package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a Campaign's EFFECTIVE {@link AiPersonality} (Req 49.2 / 49.3).
 *
 * <p>AI_Personality is stored at three levels and resolved with a strict precedence: the
 * Campaign-level override ({@link CampaignEntity#getCampaignPersonality()}) wins, otherwise the
 * Goal_Personality_Default ({@link GoalEntity#getRiskPreference()}) of the Campaign's associated Goal,
 * otherwise the Store_Default_Personality ({@link StoreEntity#getDefaultPersonality()}), and finally
 * the system fallback {@code balanced} ({@link AiPersonality#SYSTEM_FALLBACK}). The first level that
 * carries a recognized canonical value wins; an unset, blank, or non-canonical value at a level is
 * treated as "not set" and falls through to the next level (see {@link AiPersonality#parse(String)})
 * so the resolver never guesses.</p>
 *
 * <p>The core {@link #resolve(String, String, String)} method is PURE — it takes the three raw values
 * and applies the precedence with no I/O — so the resolution rule can be property-tested in isolation
 * (Property 48). The loading overloads fetch the Goal and Store for a given Campaign and delegate to
 * the pure method.</p>
 *
 * <p>Validates: Requirements 49.2, 49.3.</p>
 */
@Component
@RequiredArgsConstructor
public class PersonalityResolver {

    private final CampaignMapper campaignMapper;
    private final GoalMapper goalMapper;
    private final StoreMapper storeMapper;

    /**
     * Pure resolution of the effective personality from the three raw level values, applying the
     * precedence Campaign override &gt; Goal default &gt; Store default &gt; {@code balanced}.
     *
     * @param campaignOverride the Campaign-level AI_Personality override (may be {@code null}/blank)
     * @param goalDefault the Goal_Personality_Default (may be {@code null}/blank)
     * @param storeDefault the Store_Default_Personality (may be {@code null}/blank)
     * @return the effective {@link AiPersonality}; never {@code null}
     */
    public AiPersonality resolve(String campaignOverride, String goalDefault, String storeDefault) {
        return AiPersonality.parse(campaignOverride)
                .or(() -> AiPersonality.parse(goalDefault))
                .or(() -> AiPersonality.parse(storeDefault))
                .orElse(AiPersonality.SYSTEM_FALLBACK);
    }

    /**
     * Resolve the effective personality for a loaded {@link CampaignEntity}, fetching the associated
     * Goal (when {@code goal_id} is set) and the owning Store to obtain their defaults.
     *
     * @param campaign the campaign whose effective personality is wanted
     * @return the effective {@link AiPersonality}; never {@code null}
     */
    public AiPersonality resolveForCampaign(CampaignEntity campaign) {
        if (campaign == null) {
            return AiPersonality.SYSTEM_FALLBACK;
        }
        String goalDefault = null;
        if (campaign.getGoalId() != null) {
            GoalEntity goal = goalMapper.selectById(campaign.getGoalId());
            if (goal != null) {
                goalDefault = goal.getRiskPreference();
            }
        }
        String storeDefault = null;
        if (campaign.getStoreId() != null) {
            StoreEntity store = storeMapper.selectById(campaign.getStoreId());
            if (store != null) {
                storeDefault = store.getDefaultPersonality();
            }
        }
        return resolve(campaign.getCampaignPersonality(), goalDefault, storeDefault);
    }

    /**
     * Resolve the effective personality for a Campaign by id.
     *
     * @param campaignId the campaign id
     * @return the effective {@link AiPersonality}, or {@link Optional#empty()} when no such campaign
     *     exists
     */
    public Optional<AiPersonality> resolveForCampaign(UUID campaignId) {
        if (campaignId == null) {
            return Optional.empty();
        }
        CampaignEntity campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            return Optional.empty();
        }
        return Optional.of(resolveForCampaign(campaign));
    }
}
