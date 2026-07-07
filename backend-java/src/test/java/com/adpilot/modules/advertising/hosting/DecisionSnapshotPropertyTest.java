package com.adpilot.modules.advertising.hosting;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for the {@link DecisionSnapshot} immutability and JSON round-trip.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 35: Decision snapshot is immutable
 * and round-trips.
 *
 * <p><b>Validates: Requirements 34.2, 34.3, 37.5, 38.4, 13.6</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>A DecisionSnapshot serialized to JSON and deserialized back produces an equal object (round-trip)</li>
 *   <li>The DecisionSnapshot's collection fields are immutable after construction (unmodifiable)</li>
 *   <li>Two snapshots built with the same inputs are equal</li>
 *   <li>The JSON representation is stable across serialization/deserialization cycles</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 35: Decision snapshot is immutable and round-trip")
class DecisionSnapshotPropertyTest {

    private static final int MIN_ITERATIONS = 100;

    /**
     * Constructs an ObjectMapper matching the application's JacksonConfig.
     */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    // ================================================================================
    // Property 1: JSON round-trip produces an equal object (Req 34.2, 34.3, 13.6)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 35: Decision snapshot is immutable
     * and round-trips.
     *
     * <p><b>Validates: Requirements 34.2, 34.3, 13.6</b>
     *
     * <p>Serializing a DecisionSnapshot to JSON and deserializing back yields an
     * object equal to the original.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Round-trip: serialize → deserialize produces an equal snapshot")
    void serializeDeserializeRoundTrip(
            @ForAll("snapshots") DecisionSnapshot original) throws Exception {

        ObjectMapper mapper = createObjectMapper();

        String json = mapper.writeValueAsString(original);
        DecisionSnapshot deserialized = mapper.readValue(json, DecisionSnapshot.class);

        assertThat(deserialized)
                .as("Deserialized snapshot should equal the original")
                .isEqualTo(original);
    }

    // ================================================================================
    // Property 2: Collections are immutable after construction (Req 34.2, 37.5)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 35: Decision snapshot is immutable
     * and round-trips.
     *
     * <p><b>Validates: Requirements 34.2, 37.5</b>
     *
     * <p>The collection fields (metricInputs, inheritanceChain, effectiveBoundaries)
     * returned by getters are unmodifiable. Attempting to mutate them throws
     * UnsupportedOperationException.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Immutability: collection fields are unmodifiable after construction")
    void collectionsAreImmutable(
            @ForAll("snapshots") DecisionSnapshot snapshot) {

        // metricInputs map should be unmodifiable
        assertThatThrownBy(() -> snapshot.getMetricInputs().put("injected", BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);

        // inheritanceChain list should be unmodifiable
        assertThatThrownBy(() -> snapshot.getInheritanceChain().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);

        // effectiveBoundaries list should be unmodifiable
        assertThatThrownBy(() -> snapshot.getEffectiveBoundaries().add(
                new DecisionSnapshot.EffectiveBoundary("fake", "0", "system")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ================================================================================
    // Property 3: Two snapshots with the same inputs are equal (Req 34.3, 38.4)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 35: Decision snapshot is immutable
     * and round-trips.
     *
     * <p><b>Validates: Requirements 34.3, 38.4</b>
     *
     * <p>Constructing two DecisionSnapshot instances from the same inputs yields
     * equal objects (value equality).
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Value equality: two snapshots from the same inputs are equal")
    void sameInputsProduceEqualSnapshots(
            @ForAll("snapshotInputs") SnapshotInputs inputs) {

        DecisionSnapshot first = buildFromInputs(inputs);
        DecisionSnapshot second = buildFromInputs(inputs);

        assertThat(first)
                .as("Two snapshots built with the same inputs must be equal")
                .isEqualTo(second);

        assertThat(first.hashCode())
                .as("Equal snapshots must have the same hashCode")
                .isEqualTo(second.hashCode());
    }

    // ================================================================================
    // Property 4: JSON is stable across cycles (Req 34.2, 13.6)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 35: Decision snapshot is immutable
     * and round-trips.
     *
     * <p><b>Validates: Requirements 34.2, 13.6</b>
     *
     * <p>Serializing, deserializing, and re-serializing a DecisionSnapshot produces
     * identical JSON both times (the representation is stable/idempotent).
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Stability: JSON representation is stable across serialization cycles")
    void jsonIsStableAcrossCycles(
            @ForAll("snapshots") DecisionSnapshot original) throws Exception {

        ObjectMapper mapper = createObjectMapper();

        String json1 = mapper.writeValueAsString(original);
        DecisionSnapshot deserialized = mapper.readValue(json1, DecisionSnapshot.class);
        String json2 = mapper.writeValueAsString(deserialized);

        assertThat(json2)
                .as("Re-serialized JSON should be identical to first serialization")
                .isEqualTo(json1);
    }

    // ================================================================================
    // Records for inputs
    // ================================================================================

    record SnapshotInputs(
            LocalDateTime dataCutoff,
            int lookbackDays,
            Map<String, BigDecimal> metricInputs,
            boolean dqPassed,
            String dqReason,
            String personality,
            List<String> inheritanceChain,
            List<DecisionSnapshot.EffectiveBoundary> effectiveBoundaries,
            String riskFormulaVersion,
            BigDecimal riskScore,
            String ruleVersion,
            String currency,
            String marketplaceTimezone,
            String executionMode,
            boolean killSwitchActive,
            boolean shadowModeActive
    ) {}

    // ================================================================================
    // Generators
    // ================================================================================

    @Provide
    Arbitrary<DecisionSnapshot> snapshots() {
        return snapshotInputs().map(this::buildFromInputs);
    }

    @Provide
    Arbitrary<SnapshotInputs> snapshotInputs() {
        Arbitrary<LocalDateTime> dataCutoffs = Arbitraries.of(
                LocalDateTime.of(2024, 1, 15, 10, 30, 0),
                LocalDateTime.of(2024, 6, 1, 0, 0, 0),
                LocalDateTime.of(2025, 3, 10, 14, 45, 0),
                LocalDateTime.of(2023, 12, 31, 23, 59, 59)
        );

        Arbitrary<Integer> lookbackDays = Arbitraries.integers().between(1, 90);

        Arbitrary<Map<String, BigDecimal>> metricMaps = metricInputsArbitrary();

        Arbitrary<Boolean> booleans = Arbitraries.of(true, false);

        Arbitrary<String> dqReasons = Arbitraries.of(
                "PASSED", "DATA_STALE", "DATA_INCOMPLETE"
        );

        Arbitrary<String> personalities = Arbitraries.of(
                "conservative", "balanced", "aggressive"
        );

        Arbitrary<List<String>> chains = Arbitraries.of(
                List.of("campaign", "goal", "store"),
                List.of("store", "organization", "system"),
                List.of("campaign"),
                List.of()
        );

        Arbitrary<List<DecisionSnapshot.EffectiveBoundary>> boundaries = boundaryListArbitrary();

        Arbitrary<String> formulaVersions = Arbitraries.of("v1.0", "v1.1", "v2.0");

        Arbitrary<BigDecimal> riskScores = Arbitraries.integers().between(0, 100)
                .map(i -> new BigDecimal(i).divide(new BigDecimal(100)));

        Arbitrary<String> ruleVersions = Arbitraries.of("rule-v1", "rule-v2", "rule-v3");

        Arbitrary<String> currencies = Arbitraries.of("USD", "CNY", "EUR", "JPY");

        Arbitrary<String> timezones = Arbitraries.of(
                "America/Los_Angeles", "Asia/Shanghai", "Europe/London", "Asia/Tokyo"
        );

        Arbitrary<String> executionModes = Arbitraries.of(
                "observe_only", "recommend_only", "approval_required", "auto_execute"
        );

        // jqwik Combinators.combine supports max 8 args, so we split into two groups
        // and flatMap to combine them
        Arbitrary<PartA> partA = Combinators.combine(
                dataCutoffs, lookbackDays, metricMaps, booleans, dqReasons,
                personalities, chains, boundaries
        ).as(PartA::new);

        Arbitrary<PartB> partB = Combinators.combine(
                formulaVersions, riskScores, ruleVersions, currencies,
                timezones, executionModes, booleans, booleans
        ).as(PartB::new);

        return Combinators.combine(partA, partB).as((a, b) ->
                new SnapshotInputs(
                        a.dataCutoff(), a.lookbackDays(), a.metricInputs(),
                        a.dqPassed(), a.dqReason(), a.personality(),
                        a.inheritanceChain(), a.effectiveBoundaries(),
                        b.riskFormulaVersion(), b.riskScore(), b.ruleVersion(),
                        b.currency(), b.marketplaceTimezone(), b.executionMode(),
                        b.killSwitchActive(), b.shadowModeActive()
                ));
    }

    /** First half of the snapshot inputs (to work around Combinators.combine 8-arg limit). */
    private record PartA(
            LocalDateTime dataCutoff,
            int lookbackDays,
            Map<String, BigDecimal> metricInputs,
            boolean dqPassed,
            String dqReason,
            String personality,
            List<String> inheritanceChain,
            List<DecisionSnapshot.EffectiveBoundary> effectiveBoundaries
    ) {}

    /** Second half of the snapshot inputs. */
    private record PartB(
            String riskFormulaVersion,
            BigDecimal riskScore,
            String ruleVersion,
            String currency,
            String marketplaceTimezone,
            String executionMode,
            boolean killSwitchActive,
            boolean shadowModeActive
    ) {}

    private Arbitrary<Map<String, BigDecimal>> metricInputsArbitrary() {
        Arbitrary<String> metricKeys = Arbitraries.of(
                "acos", "spend", "sales", "clicks", "impressions", "cpc", "roas"
        );
        Arbitrary<BigDecimal> metricValues = Arbitraries.integers().between(0, 10000)
                .map(i -> new BigDecimal(i).divide(new BigDecimal(100)));

        return Arbitraries.integers().between(0, 5).flatMap(size -> {
            if (size == 0) return Arbitraries.just(Map.of());
            return metricKeys.list().ofSize(size).uniqueElements()
                    .flatMap(keys -> metricValues.list().ofSize(keys.size())
                            .map(values -> {
                                Map<String, BigDecimal> map = new HashMap<>();
                                for (int i = 0; i < keys.size(); i++) {
                                    map.put(keys.get(i), values.get(i));
                                }
                                return map;
                            }));
        });
    }

    private Arbitrary<List<DecisionSnapshot.EffectiveBoundary>> boundaryListArbitrary() {
        Arbitrary<String> limitNames = Arbitraries.of(
                "MIN_BID", "MAX_BID", "MAX_CPC", "MAX_DAILY_BUDGET", "MIN_DAILY_BUDGET"
        );
        Arbitrary<String> values = Arbitraries.integers().between(1, 1000)
                .map(i -> new BigDecimal(i).divide(new BigDecimal(100)).toPlainString());
        Arbitrary<String> sourceLevels = Arbitraries.of(
                "campaign", "goal", "store", "organization", "system"
        );

        Arbitrary<DecisionSnapshot.EffectiveBoundary> singleBoundary =
                Combinators.combine(limitNames, values, sourceLevels)
                        .as(DecisionSnapshot.EffectiveBoundary::new);

        return singleBoundary.list().ofMinSize(0).ofMaxSize(5);
    }

    // ================================================================================
    // Helpers
    // ================================================================================

    private DecisionSnapshot buildFromInputs(SnapshotInputs inputs) {
        return DecisionSnapshot.builder()
                .dataCutoff(inputs.dataCutoff())
                .lookbackDays(inputs.lookbackDays())
                .metricInputs(inputs.metricInputs())
                .dqGateResult(new DecisionSnapshot.DqGateResult(
                        inputs.dqPassed(), inputs.dqReason()))
                .personality(inputs.personality())
                .inheritanceChain(inputs.inheritanceChain())
                .effectiveBoundaries(inputs.effectiveBoundaries())
                .riskFormulaVersion(inputs.riskFormulaVersion())
                .riskScore(inputs.riskScore())
                .ruleVersion(inputs.ruleVersion())
                .currency(inputs.currency())
                .marketplaceTimezone(inputs.marketplaceTimezone())
                .executionMode(inputs.executionMode())
                .killSwitchActive(inputs.killSwitchActive())
                .shadowModeActive(inputs.shadowModeActive())
                .build();
    }
}
