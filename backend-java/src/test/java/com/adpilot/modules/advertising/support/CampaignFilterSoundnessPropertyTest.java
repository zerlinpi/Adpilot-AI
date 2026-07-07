package com.adpilot.modules.advertising.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.adpilot.modules.advertising.support.CampaignFilter.SmartFilter;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Property 6: Filter soundness and completeness.
 *
 * <p>For any dataset of {@link CampaignView}s and any active filter
 * combination, {@link CampaignFilter#apply(List)}:
 * <ul>
 *   <li><strong>Soundness</strong> — contains <em>no</em> item that fails any
 *       active filter (scoped to the active store);</li>
 *   <li><strong>Completeness</strong> — contains <em>every</em> item that
 *       satisfies all active filters.</li>
 * </ul>
 *
 * <p>The production {@link CampaignFilter#matches(CampaignView)} predicate is
 * cross-checked against an independent oracle ({@link #oracleMatches}) that
 * re-implements the documented AND-combined filter semantics from scratch, so
 * the two derivations must agree on every generated case.
 *
 * <p>Property 6 also covers creative-asset filtering (Phase 4), but
 * {@code creative_assets} do not exist yet, so this test focuses on campaigns
 * as the design notes.
 *
 * <p>Tag: {@code Feature: app-functionality-completion, Property 6: Filter
 * soundness and completeness}
 *
 * <p>Validates: Requirements 19.4, 29.3
 */
@Label("Feature: app-functionality-completion, Property 6: Filter soundness and completeness")
class CampaignFilterSoundnessPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    // ----- Soundness: nothing in the result fails any active filter -----

    @Property(tries = MIN_ITERATIONS)
    @Label("apply(dataset) returns no campaign that fails any active filter (soundness)")
    void resultContainsNoFailingItem(
            @ForAll("datasets") @Size(max = 30) List<CampaignView> dataset,
            @ForAll("filterSpecs") FilterSpec spec) {
        CampaignFilter filter = spec.toFilter();
        List<CampaignView> result = filter.apply(dataset);

        for (CampaignView c : result) {
            assertThat(oracleMatches(spec, c))
                    .as("result item must satisfy every active filter: %s under %s", c, spec)
                    .isTrue();
        }
    }

    // ----- Completeness: every satisfying item is in the result -----

    @Property(tries = MIN_ITERATIONS)
    @Label("apply(dataset) returns every campaign that satisfies all active filters (completeness)")
    void resultContainsEverySatisfyingItem(
            @ForAll("datasets") @Size(max = 30) List<CampaignView> dataset,
            @ForAll("filterSpecs") FilterSpec spec) {
        CampaignFilter filter = spec.toFilter();
        List<CampaignView> result = filter.apply(dataset);

        for (CampaignView c : dataset) {
            if (oracleMatches(spec, c)) {
                assertThat(result)
                        .as("every satisfying campaign must be present in the result: %s under %s",
                                c, spec)
                        .contains(c);
            }
        }
    }

    // ----- Soundness + completeness together: result equals the oracle subset -----

    @Property(tries = MIN_ITERATIONS)
    @Label("apply(dataset) equals exactly the oracle-selected subset, order preserved")
    void resultEqualsOracleSubset(
            @ForAll("datasets") @Size(max = 30) List<CampaignView> dataset,
            @ForAll("filterSpecs") FilterSpec spec) {
        CampaignFilter filter = spec.toFilter();
        List<CampaignView> result = filter.apply(dataset);

        List<CampaignView> expected =
                dataset.stream().filter(c -> oracleMatches(spec, c)).toList();

        assertThat(result)
                .as("apply() must equal the independently computed subset under %s", spec)
                .containsExactlyElementsOf(expected);
    }

    // ----- Independent oracle of the documented filter semantics -----

    /**
     * Re-implements the AND-combined matching rules independently of
     * {@link CampaignFilter}. A blank/null filter value imposes no constraint.
     */
    private static boolean oracleMatches(FilterSpec s, CampaignView c) {
        if (isActive(s.storeId) && !norm(s.storeId).equals(c.storeId())) {
            return false;
        }
        if (isActive(s.adType) && !norm(s.adType).equalsIgnoreCase(nz(c.adType()))) {
            return false;
        }
        if (isActive(s.portfolioId) && !norm(s.portfolioId).equals(c.portfolioId())) {
            return false;
        }
        if (isActive(s.parentAsin) && !norm(s.parentAsin).equals(c.parentAsin())) {
            return false;
        }
        if (isActive(s.targetingGoal) && !norm(s.targetingGoal).equalsIgnoreCase(nz(c.targetingGoal()))) {
            return false;
        }
        if (isActive(s.status) && !norm(s.status).equalsIgnoreCase(nz(c.status()))) {
            return false;
        }
        if (s.targetAcosMin != null) {
            if (c.targetAcos() == null || c.targetAcos().compareTo(s.targetAcosMin) < 0) {
                return false;
            }
        }
        if (s.targetAcosMax != null) {
            if (c.targetAcos() == null || c.targetAcos().compareTo(s.targetAcosMax) > 0) {
                return false;
            }
        }
        if (s.smartFilter != null && !smartMatches(s.smartFilter, c)) {
            return false;
        }
        return true;
    }

    private static boolean smartMatches(SmartFilter f, CampaignView c) {
        return switch (f) {
            case AI_MANAGED -> c.aiManaged();
            case HOSTED -> c.hostingEnabled();
            case UNHOSTED -> !c.hostingEnabled();
            case OVER_TARGET -> c.targetAcos() != null && c.recentAcos() != null
                    && c.recentAcos().compareTo(c.targetAcos()) > 0;
            case UNDER_TARGET -> c.targetAcos() != null && c.recentAcos() != null
                    && c.recentAcos().compareTo(c.targetAcos()) <= 0;
        };
    }

    private static boolean isActive(String v) {
        return v != null && !v.isBlank();
    }

    private static String norm(String v) {
        return v.trim();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    // ----- Filter specification (nullable fields => inactive) -----

    /** A generated, possibly-partial set of filter parameters. */
    record FilterSpec(
            String storeId,
            String adType,
            String portfolioId,
            String parentAsin,
            String targetingGoal,
            String status,
            BigDecimal targetAcosMin,
            BigDecimal targetAcosMax,
            SmartFilter smartFilter) {

        CampaignFilter toFilter() {
            return CampaignFilter.builder()
                    .storeId(storeId)
                    .adType(adType)
                    .portfolioId(portfolioId)
                    .parentAsin(parentAsin)
                    .targetingGoal(targetingGoal)
                    .status(status)
                    .targetAcosMin(targetAcosMin)
                    .targetAcosMax(targetAcosMax)
                    .smartFilter(smartFilter)
                    .build();
        }

        @Override
        public String toString() {
            return "FilterSpec{store=" + storeId + ", adType=" + adType
                    + ", portfolio=" + portfolioId + ", asin=" + parentAsin
                    + ", goal=" + targetingGoal + ", status=" + status
                    + ", acos=[" + targetAcosMin + "," + targetAcosMax + "]"
                    + ", smart=" + smartFilter + "}";
        }
    }

    // ----- Generators -----

    // Domains shared between dataset and filters so filters select a real mix.
    private static final List<String> STORES = List.of("store-A", "store-B", "store-C");
    private static final List<String> AD_TYPES = List.of("SP", "SB", "SD");
    private static final List<String> PORTFOLIOS = List.of("pf-1", "pf-2");
    private static final List<String> ASINS = List.of("B00000001", "B00000002", "B00000003");
    private static final List<String> GOALS = List.of("sales", "awareness", "conversions");
    private static final List<String> STATUSES = List.of("enabled", "paused", "archived");
    private static final List<BigDecimal> ACOS = List.of(
            new BigDecimal("0.10"), new BigDecimal("0.25"), new BigDecimal("0.40"));

    @Provide
    Arbitrary<List<CampaignView>> datasets() {
        return campaignViews().list().ofMaxSize(30);
    }

    @Provide
    Arbitrary<CampaignView> campaignViews() {
        Arbitrary<String> store = Arbitraries.of(STORES);
        Arbitrary<String> adType = Arbitraries.of(AD_TYPES).map(this::randomizeCase);
        Arbitrary<String> portfolio = orNull(Arbitraries.of(PORTFOLIOS));
        Arbitrary<String> asin = orNull(Arbitraries.of(ASINS));
        Arbitrary<String> goal = orNull(Arbitraries.of(GOALS).map(this::randomizeCase));
        Arbitrary<String> status = Arbitraries.of(STATUSES).map(this::randomizeCase);
        Arbitrary<BigDecimal> targetAcos = orNull(Arbitraries.of(ACOS));
        Arbitrary<BigDecimal> recentAcos = orNull(Arbitraries.of(ACOS));
        Arbitrary<Boolean> aiManaged = Arbitraries.of(true, false);
        Arbitrary<Boolean> hostingEnabled = Arbitraries.of(true, false);

        return Combinators.combine(store, adType, portfolio, asin, goal, status,
                        targetAcos, recentAcos)
                .as((st, at, pf, as, go, sts, ta, ra) ->
                        new Object[] {st, at, pf, as, go, sts, ta, ra})
                .flatMap(base -> Combinators.combine(aiManaged, hostingEnabled)
                        .as((ai, host) -> new CampaignView(
                                (String) base[0],
                                (String) base[1],
                                (String) base[2],
                                (String) base[3],
                                (String) base[4],
                                (String) base[5],
                                (BigDecimal) base[6],
                                (BigDecimal) base[7],
                                ai,
                                host)));
    }

    @Provide
    Arbitrary<FilterSpec> filterSpecs() {
        // Each filter dimension is frequently inactive (null/blank) so the empty
        // filter and partial combinations are well exercised, and active values
        // are drawn from the dataset domains (with case variation / blanks).
        // store / portfolio / asin match case-sensitively, so draw exact domain
        // values (with inactive markers) to meaningfully exercise scoping.
        Arbitrary<String> store = filterString(STORES, false);
        Arbitrary<String> portfolio = filterString(PORTFOLIOS, false);
        Arbitrary<String> asin = filterString(ASINS, false);
        // adType / goal / status match case-insensitively, so vary the case.
        Arbitrary<String> adType = filterString(AD_TYPES, true);
        Arbitrary<String> goal = filterString(GOALS, true);
        Arbitrary<String> status = filterString(STATUSES, true);
        Arbitrary<BigDecimal> acosMin = orNull(Arbitraries.of(ACOS));
        Arbitrary<BigDecimal> acosMax = orNull(Arbitraries.of(ACOS));
        Arbitrary<SmartFilter> smart = orNull(Arbitraries.of(SmartFilter.values()));

        return Combinators.combine(store, adType, portfolio, asin, goal, status, acosMin, acosMax)
                .as((st, at, pf, as, go, sts, mn, mx) ->
                        new Object[] {st, at, pf, as, go, sts, mn, mx})
                .flatMap(base -> smart.map(sf -> new FilterSpec(
                        (String) base[0],
                        (String) base[1],
                        (String) base[2],
                        (String) base[3],
                        (String) base[4],
                        (String) base[5],
                        (BigDecimal) base[6],
                        (BigDecimal) base[7],
                        sf)));
    }

    /**
     * A filter value drawn from a domain, with case variation, plus a healthy
     * share of inactive markers (null and blank tokens).
     */
    private Arbitrary<String> filterString(List<String> domain, boolean varyCase) {
        Arbitrary<String> active = varyCase
                ? Arbitraries.of(domain).map(this::randomizeCase)
                : Arbitraries.of(domain);
        Arbitrary<String> inactive = Arbitraries.of((String) null, "", "   ");
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(2, active),
                net.jqwik.api.Tuple.of(3, inactive));
    }

    private <T> Arbitrary<T> orNull(Arbitrary<T> arb) {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, arb),
                net.jqwik.api.Tuple.of(2, Arbitraries.just(null)));
    }

    /** Flip the case of alternating characters to exercise case-insensitivity. */
    private String randomizeCase(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            sb.append((i % 2 == 0) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
        }
        return sb.toString();
    }
}
