package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Pure, side-effect-free predicate over the filterable attributes of a Campaign
 * (Requirement 19.4). Given a combination of optional filters, it decides
 * whether a single {@link CampaignView} is selected, and can refine a list to
 * exactly the matching subset.
 *
 * <p><strong>Semantics.</strong> The filters are combined with logical
 * <em>AND</em>: a campaign is selected iff it satisfies <em>every</em> active
 * filter. A filter is <em>inactive</em> (imposes no constraint) when its value
 * is {@code null} or blank, so the empty filter selects everything within the
 * active store. This guarantees the soundness/completeness contract of
 * Property 6 (task 10.2): the result contains every item satisfying all active
 * filters and no item that fails any active filter.
 *
 * <p>The supported filters are: store scope ({@code storeId}), ad type
 * ({@code adType}: SP / SB / SD), {@code portfolioId}, {@code parentAsin},
 * {@code targetingGoal}, {@code status}, an inclusive Target ACoS range
 * ({@code targetAcosMin} / {@code targetAcosMax}), and a {@code smartFilter}
 * preset.
 *
 * <p>Matching rules:
 * <ul>
 *   <li>{@code storeId}, {@code portfolioId}, {@code parentAsin} — exact,
 *       case-sensitive equality (they are identifiers / ASINs).</li>
 *   <li>{@code adType}, {@code targetingGoal}, {@code status} — case-insensitive
 *       equality.</li>
 *   <li>{@code targetAcosMin} / {@code targetAcosMax} — the campaign's
 *       {@code targetAcos} must be non-null and fall within the inclusive
 *       bound(s); a campaign with no Target ACoS fails any active range
 *       bound.</li>
 *   <li>{@code smartFilter} — see {@link SmartFilter}; an unrecognized token is
 *       treated as inactive.</li>
 * </ul>
 *
 * <p>Instances are immutable; build them with {@link #builder()}.
 */
public final class CampaignFilter {

    private final String storeId;
    private final String adType;
    private final String portfolioId;
    private final String parentAsin;
    private final String targetingGoal;
    private final String status;
    private final BigDecimal targetAcosMin;
    private final BigDecimal targetAcosMax;
    private final SmartFilter smartFilter;

    private CampaignFilter(Builder b) {
        this.storeId = blankToNull(b.storeId);
        this.adType = blankToNull(b.adType);
        this.portfolioId = blankToNull(b.portfolioId);
        this.parentAsin = blankToNull(b.parentAsin);
        this.targetingGoal = blankToNull(b.targetingGoal);
        this.status = blankToNull(b.status);
        this.targetAcosMin = b.targetAcosMin;
        this.targetAcosMax = b.targetAcosMax;
        this.smartFilter = b.smartFilter;
    }

    /**
     * Decide whether a single campaign satisfies every active filter.
     *
     * @param c the campaign projection (must not be {@code null})
     * @return {@code true} iff the campaign passes all active filters
     */
    public boolean matches(CampaignView c) {
        if (c == null) {
            return false;
        }
        if (storeId != null && !storeId.equals(c.storeId())) {
            return false;
        }
        if (adType != null && !adType.equalsIgnoreCase(nz(c.adType()))) {
            return false;
        }
        if (portfolioId != null && !portfolioId.equals(c.portfolioId())) {
            return false;
        }
        if (parentAsin != null && !parentAsin.equals(c.parentAsin())) {
            return false;
        }
        if (targetingGoal != null && !targetingGoal.equalsIgnoreCase(nz(c.targetingGoal()))) {
            return false;
        }
        if (status != null && !status.equalsIgnoreCase(nz(c.status()))) {
            return false;
        }
        if (targetAcosMin != null) {
            if (c.targetAcos() == null || c.targetAcos().compareTo(targetAcosMin) < 0) {
                return false;
            }
        }
        if (targetAcosMax != null) {
            if (c.targetAcos() == null || c.targetAcos().compareTo(targetAcosMax) > 0) {
                return false;
            }
        }
        if (smartFilter != null && !smartFilter.test(c)) {
            return false;
        }
        return true;
    }

    /** This filter as a {@link Predicate} for use in streams. */
    public Predicate<CampaignView> asPredicate() {
        return this::matches;
    }

    /**
     * Refine a list to exactly the campaigns that satisfy every active filter,
     * preserving the input order.
     *
     * @param campaigns the campaigns to filter (must not be {@code null})
     * @return a new list containing only the matching campaigns
     */
    public List<CampaignView> apply(List<CampaignView> campaigns) {
        return campaigns.stream().filter(this::matches).collect(Collectors.toList());
    }

    // ----- accessors (used by the service to translate filters to SQL) -----

    public String getStoreId() {
        return storeId;
    }

    public String getAdType() {
        return adType;
    }

    public String getPortfolioId() {
        return portfolioId;
    }

    public String getParentAsin() {
        return parentAsin;
    }

    public String getTargetingGoal() {
        return targetingGoal;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getTargetAcosMin() {
        return targetAcosMin;
    }

    public BigDecimal getTargetAcosMax() {
        return targetAcosMax;
    }

    public SmartFilter getSmartFilter() {
        return smartFilter;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Smart-filter presets (智能筛选). Each preset is a named, deterministic
     * predicate over a {@link CampaignView}. Unknown tokens resolve to
     * {@code null} (see {@link #from(String)}) and therefore impose no
     * constraint.
     */
    public enum SmartFilter {
        /** Campaigns currently managed by AI (AI入格). */
        AI_MANAGED {
            @Override
            public boolean test(CampaignView c) {
                return c.aiManaged();
            }
        },
        /** Campaigns currently under AI hosting. */
        HOSTED {
            @Override
            public boolean test(CampaignView c) {
                return c.hostingEnabled();
            }
        },
        /** Campaigns not under AI hosting. */
        UNHOSTED {
            @Override
            public boolean test(CampaignView c) {
                return !c.hostingEnabled();
            }
        },
        /** Hosted campaigns whose recent ACoS exceeds their Target ACoS. */
        OVER_TARGET {
            @Override
            public boolean test(CampaignView c) {
                return c.targetAcos() != null && c.recentAcos() != null
                        && c.recentAcos().compareTo(c.targetAcos()) > 0;
            }
        },
        /** Hosted campaigns whose recent ACoS is at or below their Target ACoS. */
        UNDER_TARGET {
            @Override
            public boolean test(CampaignView c) {
                return c.targetAcos() != null && c.recentAcos() != null
                        && c.recentAcos().compareTo(c.targetAcos()) <= 0;
            }
        };

        /** The predicate this preset represents. */
        public abstract boolean test(CampaignView c);

        /**
         * Parse a smart-filter token case-insensitively. Returns {@code null}
         * for {@code null}, blank, or unrecognized tokens so that callers treat
         * an unknown preset as "no smart filter" rather than an error.
         */
        public static SmartFilter from(String token) {
            if (token == null || token.isBlank()) {
                return null;
            }
            try {
                return SmartFilter.valueOf(token.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /** Builder for {@link CampaignFilter}. */
    public static final class Builder {
        private String storeId;
        private String adType;
        private String portfolioId;
        private String parentAsin;
        private String targetingGoal;
        private String status;
        private BigDecimal targetAcosMin;
        private BigDecimal targetAcosMax;
        private SmartFilter smartFilter;

        public Builder storeId(String v) {
            this.storeId = v;
            return this;
        }

        public Builder adType(String v) {
            this.adType = v;
            return this;
        }

        public Builder portfolioId(String v) {
            this.portfolioId = v;
            return this;
        }

        public Builder parentAsin(String v) {
            this.parentAsin = v;
            return this;
        }

        public Builder targetingGoal(String v) {
            this.targetingGoal = v;
            return this;
        }

        public Builder status(String v) {
            this.status = v;
            return this;
        }

        public Builder targetAcosMin(BigDecimal v) {
            this.targetAcosMin = v;
            return this;
        }

        public Builder targetAcosMax(BigDecimal v) {
            this.targetAcosMax = v;
            return this;
        }

        public Builder smartFilter(SmartFilter v) {
            this.smartFilter = v;
            return this;
        }

        /** Set the smart filter from a raw token (unrecognized tokens are ignored). */
        public Builder smartFilter(String token) {
            this.smartFilter = SmartFilter.from(token);
            return this;
        }

        public CampaignFilter build() {
            return new CampaignFilter(this);
        }
    }
}
