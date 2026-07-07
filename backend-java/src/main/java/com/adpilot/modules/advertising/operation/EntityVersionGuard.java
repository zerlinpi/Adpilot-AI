package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.mapper.TargetMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the correct versioned-entity mapper for an Operation's {@code entityType} and applies the
 * {@link OptimisticLockGuard} pure version claim, so the {@code createOperation} pipeline can run the
 * optimistic-version check (Req 5.4/5.5) without itself knowing the concrete entity type.
 *
 * <p>It indexes the {@link BaseMapper}s of the versioned advertising entities Requirement 5.4
 * enumerates — Campaign, Goal, Ad_Group, Keyword, Target, and Negative_Keyword (each carries a
 * MyBatis-Plus {@code @Version} column) — by their canonical {@code entityType} machine value. When
 * an Operation supplies the version its view was loaded with, the guard claims the entity at that
 * version ({@code UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?}); a zero-row
 * result is a stale view and is rejected with a {@link VersionConflictException} (Req 5.5). The claim
 * bumps only the {@code version} column and never the entity's Amazon-confirmed value, so it is safe
 * inside the side-effect-free first transaction (Req 6.1).</p>
 *
 * <p>Validates: Requirements 5.4, 5.5.</p>
 */
@Component
public class EntityVersionGuard {

    /** Canonical {@code entityType} machine values for the versioned advertising entities. */
    static final String CAMPAIGN = "campaign";
    static final String GOAL = "goal";
    static final String AD_GROUP = "ad_group";
    static final String KEYWORD = "keyword";
    static final String TARGET = "target";
    static final String NEGATIVE_KEYWORD = "negative_keyword";

    private final OptimisticLockGuard optimisticLockGuard;
    private final Map<String, BaseMapper<?>> mappersByEntityType = new HashMap<>();

    public EntityVersionGuard(OptimisticLockGuard optimisticLockGuard,
                              CampaignMapper campaignMapper,
                              GoalMapper goalMapper,
                              AdGroupMapper adGroupMapper,
                              KeywordMapper keywordMapper,
                              TargetMapper targetMapper,
                              NegativeKeywordMapper negativeKeywordMapper) {
        this.optimisticLockGuard = optimisticLockGuard;
        mappersByEntityType.put(CAMPAIGN, campaignMapper);
        mappersByEntityType.put(GOAL, goalMapper);
        mappersByEntityType.put(AD_GROUP, adGroupMapper);
        mappersByEntityType.put(KEYWORD, keywordMapper);
        mappersByEntityType.put(TARGET, targetMapper);
        mappersByEntityType.put(NEGATIVE_KEYWORD, negativeKeywordMapper);
    }

    /**
     * @return {@code true} if {@code entityType} is one of the versioned advertising entities this
     *         guard can claim.
     */
    public boolean isVersioned(String entityType) {
        return entityType != null && mappersByEntityType.containsKey(entityType);
    }

    /**
     * Apply the optimistic-lock version guard for an Operation when a version was supplied.
     *
     * <p>When {@code expectedVersion} is {@code null} the check is skipped (the caller did not load a
     * versioned view, or the {@code entityType} has no version column). When a version IS supplied
     * for a versioned entity, the entity is claimed at that version and a stale view is rejected
     * (Req 5.4/5.5). A supplied version for an unknown/non-versioned {@code entityType} is also
     * skipped rather than failing, since the version guard only applies to entities that have a
     * version column.
     *
     * @param entityType      the Operation's entity type (canonical machine value)
     * @param entityId        the target object's id
     * @param expectedVersion the version the operator's view was loaded with, or {@code null}
     * @throws VersionConflictException when the object's version changed since the view loaded it
     */
    public void guardIfVersioned(String entityType, UUID entityId, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        BaseMapper<?> mapper = mappersByEntityType.get(entityType);
        if (mapper == null) {
            return;
        }
        optimisticLockGuard.guardVersion(mapper, entityType, entityId, expectedVersion);
    }
}
