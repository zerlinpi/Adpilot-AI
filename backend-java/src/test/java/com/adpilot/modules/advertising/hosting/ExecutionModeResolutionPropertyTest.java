package com.adpilot.modules.advertising.hosting;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the {@link ExecutionModeResolverImpl#resolve(String, String, String)}
 * pure method.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 20: Execution mode resolution
 *
 * <p><b>Validates: Requirements 7.2</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>Campaign mode always wins when it's a valid recognized value</li>
 *   <li>Goal mode wins when campaign mode is null/blank/unrecognized</li>
 *   <li>Store mode wins when both campaign and goal are null/blank/unrecognized</li>
 *   <li>Defaults to OBSERVE_ONLY when all levels are null/blank/unrecognized</li>
 *   <li>Result is never null</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 20: Execution mode resolution")
class ExecutionModeResolutionPropertyTest {

    private static final int MIN_ITERATIONS = 150;

    private final ExecutionModeResolverImpl resolver =
            new ExecutionModeResolverImpl(null, new ObjectMapper());

    // ================================================================================
    // Property 1: Campaign mode always wins when it's a valid recognized value
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 20: Execution mode resolution.
     *
     * <p><b>Validates: Requirements 7.2</b>
     *
     * <p>When the campaign level carries a valid execution mode string, the resolved
     * mode equals that value regardless of what the goal or store levels carry.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Campaign mode always wins when it is a valid recognized value")
    void campaignModeWinsWhenValid(
            @ForAll("validModeStrings") String campaignMode,
            @ForAll("anyModeStrings") String goalMode,
            @ForAll("anyModeStrings") String storeMode) {

        ExecutionMode result = resolver.resolve(campaignMode, goalMode, storeMode);

        ExecutionMode expected = ExecutionMode.parse(campaignMode);
        assertThat(expected).as("Precondition: campaignMode must parse to a valid mode").isNotNull();
        assertThat(result)
                .as("Campaign mode should win when it is a valid recognized value")
                .isEqualTo(expected);
    }

    // ================================================================================
    // Property 2: Goal mode wins when campaign mode is null/blank/unrecognized
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 20: Execution mode resolution.
     *
     * <p><b>Validates: Requirements 7.2</b>
     *
     * <p>When campaign mode is null/blank/unrecognized and goal carries a valid mode,
     * the resolved mode equals the goal value.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Goal mode wins when campaign mode is null/blank/unrecognized")
    void goalModeWinsWhenCampaignInvalid(
            @ForAll("invalidModeStrings") String campaignMode,
            @ForAll("validModeStrings") String goalMode,
            @ForAll("anyModeStrings") String storeMode) {

        ExecutionMode result = resolver.resolve(campaignMode, goalMode, storeMode);

        ExecutionMode expected = ExecutionMode.parse(goalMode);
        assertThat(expected).as("Precondition: goalMode must parse to a valid mode").isNotNull();
        assertThat(result)
                .as("Goal mode should win when campaign is null/blank/unrecognized")
                .isEqualTo(expected);
    }

    // ================================================================================
    // Property 3: Store mode wins when both campaign and goal are null/blank/unrecognized
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 20: Execution mode resolution.
     *
     * <p><b>Validates: Requirements 7.2</b>
     *
     * <p>When both campaign and goal modes are null/blank/unrecognized and store carries
     * a valid mode, the resolved mode equals the store value.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Store mode wins when both campaign and goal are null/blank/unrecognized")
    void storeModeWinsWhenCampaignAndGoalInvalid(
            @ForAll("invalidModeStrings") String campaignMode,
            @ForAll("invalidModeStrings") String goalMode,
            @ForAll("validModeStrings") String storeMode) {

        ExecutionMode result = resolver.resolve(campaignMode, goalMode, storeMode);

        ExecutionMode expected = ExecutionMode.parse(storeMode);
        assertThat(expected).as("Precondition: storeMode must parse to a valid mode").isNotNull();
        assertThat(result)
                .as("Store mode should win when campaign and goal are null/blank/unrecognized")
                .isEqualTo(expected);
    }

    // ================================================================================
    // Property 4: Defaults to OBSERVE_ONLY when all levels are null/blank/unrecognized
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 20: Execution mode resolution.
     *
     * <p><b>Validates: Requirements 7.2</b>
     *
     * <p>When all three levels carry null/blank/unrecognized values, the resolved mode
     * defaults to OBSERVE_ONLY.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Defaults to OBSERVE_ONLY when all levels are null/blank/unrecognized")
    void defaultsToObserveOnlyWhenAllInvalid(
            @ForAll("invalidModeStrings") String campaignMode,
            @ForAll("invalidModeStrings") String goalMode,
            @ForAll("invalidModeStrings") String storeMode) {

        ExecutionMode result = resolver.resolve(campaignMode, goalMode, storeMode);

        assertThat(result)
                .as("Should default to OBSERVE_ONLY when no level defines a valid mode")
                .isEqualTo(ExecutionMode.OBSERVE_ONLY);
    }

    // ================================================================================
    // Property 5: Result is never null
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 20: Execution mode resolution.
     *
     * <p><b>Validates: Requirements 7.2</b>
     *
     * <p>For any combination of input strings (valid, invalid, null, blank), the
     * resolve method never returns null.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Result is never null for any input combination")
    void resultIsNeverNull(
            @ForAll("anyModeStrings") String campaignMode,
            @ForAll("anyModeStrings") String goalMode,
            @ForAll("anyModeStrings") String storeMode) {

        ExecutionMode result = resolver.resolve(campaignMode, goalMode, storeMode);

        assertThat(result)
                .as("resolve() must never return null")
                .isNotNull();
    }

    // ================================================================================
    // Generators
    // ================================================================================

    /**
     * Generates valid execution mode strings (canonical values with optional case variations).
     */
    @Provide
    Arbitrary<String> validModeStrings() {
        return Arbitraries.of(
                "observe_only",
                "recommend_only",
                "approval_required",
                "auto_execute",
                "OBSERVE_ONLY",
                "RECOMMEND_ONLY",
                "APPROVAL_REQUIRED",
                "AUTO_EXECUTE",
                "Observe_Only",
                "Auto_Execute"
        );
    }

    /**
     * Generates strings that will NOT parse to a valid ExecutionMode:
     * null, blank, or unrecognized values.
     */
    @Provide
    Arbitrary<String> invalidModeStrings() {
        return Arbitraries.of(
                null,
                "",
                "   ",
                "unknown",
                "invalid_mode",
                "auto",
                "observe",
                "none",
                "disabled",
                "xyz123",
                "approve",
                "execute_auto"
        );
    }

    /**
     * Generates any mode string — valid, invalid, null, or blank — to test that
     * the resolver handles all inputs gracefully.
     */
    @Provide
    Arbitrary<String> anyModeStrings() {
        return Arbitraries.oneOf(validModeStrings(), invalidModeStrings());
    }
}
