package com.adpilot.modules.advertising.converter;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the machine-value-only emission boundary
 * {@link AdvertisingEnumEmitter}, the single authoritative point through which the four enumerated
 * advertising fields — AI_Hosting_Status, AI_Personality, Optimization_Goal, and Object_Status — are
 * projected onto every advertising response object by the {@code *Converter} classes (e.g.
 * {@link CampaignConverter}, {@link KeywordConverter}, {@link GoalConverter},
 * {@link NegativeKeywordConverter}, {@link AdGroupConverter}).
 *
 * <p>Feature: advertising-workspace-rework, Property 34: Backend emits only machine-value enums.
 *
 * <p>Validates: Requirements 14.7, 48.4.
 *
 * <p><em>For any</em> raw stored value — a canonical machine value, a recognized legacy value, a
 * Chinese display string (including the retired 稳健型 alternate name), a blank, or arbitrary unicode
 * noise — every emitter method returns ONLY the canonical {@code Machine_Value_Enum} value for its
 * enum or {@code null}; it never leaks a Chinese display string and never emits any non-machine value.
 * Concretely, every non-null emission is a member of that enum's canonical machine-value set, contains
 * no CJK character, and matches the strict lowercase-{@code [a-z_]} machine-value shape. The
 * boolean-driven AI_Hosting_Status is a total function that always yields {@code hosted|not_hosted}.
 *
 * <p>The oracle is independent of the production code: the test enumerates the canonical machine-value
 * set for each enum and the forbidden Chinese display strings directly, and the generator deliberately
 * mixes legitimate values with display strings and noise so the assertion exercises the defensive
 * read-time boundary across the whole input space.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 34: Backend emits only machine-value enums")
class MachineValueOnlyEmissionPropertyTest {

    /** Canonical Object_Status machine values (Req 16.1, 48.4). */
    private static final Set<String> OBJECT_STATUS = Set.of("enabled", "paused", "archived");

    /** Canonical Optimization_Goal machine values (Req 57.1, 48.4). */
    private static final Set<String> OPTIMIZATION_GOAL =
            Set.of("profit_first", "sales_growth", "rank", "clearance");

    /** Canonical AI_Personality machine values (Req 49.1, 48.4). */
    private static final Set<String> AI_PERSONALITY = Set.of("conservative", "balanced", "aggressive");

    /** Canonical AI_Hosting_Status machine values (Req 48.4). */
    private static final Set<String> AI_HOSTING_STATUS = Set.of("hosted", "not_hosted");

    /**
     * Chinese display strings the Backend contract must NEVER emit — display translation is the
     * Frontend's responsibility (Req 48.5). Includes the retired AI_Personality alternate name 稳健型.
     */
    private static final Set<String> FORBIDDEN_DISPLAY_STRINGS = Set.of(
            // AI_Hosting_Status displays
            "已托管", "未托管", "托管中",
            // AI_Personality displays (incl. retired 稳健型)
            "常规型", "平衡型", "激进型", "稳健型",
            // Optimization_Goal displays
            "利润优先", "销量增长", "排名提升", "清仓",
            // Object_Status displays
            "启用", "暂停", "已归档", "已启用", "已暂停", "归档");

    /** Matches only a non-empty lowercase machine-value token ({@code [a-z_]+}). */
    private static final java.util.regex.Pattern MACHINE_VALUE_SHAPE =
            java.util.regex.Pattern.compile("[a-z_]+");

    /**
     * Feature: advertising-workspace-rework, Property 34: Backend emits only machine-value enums.
     *
     * <p>Validates: Requirements 14.7, 48.4.
     *
     * <p>For an arbitrary raw stored value and hosting flag, every one of the four enum emitters
     * returns a canonical machine value or {@code null} and never a Chinese display string.
     */
    @Property(tries = 200)
    void emittersNeverLeakDisplayStrings(@ForAll("rawValues") String raw,
                                         @ForAll boolean hostingEnabled) {
        // Object_Status: canonical machine value or null, never a display string.
        assertMachineValueOrNull(AdvertisingEnumEmitter.objectStatus(raw), OBJECT_STATUS);

        // Optimization_Goal: canonical machine value or null, never a display string.
        assertMachineValueOrNull(AdvertisingEnumEmitter.optimizationGoal(raw), OPTIMIZATION_GOAL);

        // AI_Personality: canonical machine value or null, never a display string (incl. 稳健型).
        assertMachineValueOrNull(AdvertisingEnumEmitter.aiPersonality(raw), AI_PERSONALITY);

        // AI_Hosting_Status: total function of the boolean flag — always a canonical machine value.
        String hostingStatus = AdvertisingEnumEmitter.aiHostingStatus(hostingEnabled);
        assertThat(hostingStatus).isNotNull();
        assertThat(AI_HOSTING_STATUS).contains(hostingStatus);
        assertThat(FORBIDDEN_DISPLAY_STRINGS).doesNotContain(hostingStatus);
        assertMachineShape(hostingStatus);
        assertThat(hostingStatus).isEqualTo(hostingEnabled ? "hosted" : "not_hosted");
    }

    /**
     * Assert an emitted enum value is either {@code null} or a member of the enum's canonical
     * machine-value set — and in the non-null case is never a forbidden display string, carries no CJK
     * character, and has the strict lowercase machine-value shape.
     */
    private static void assertMachineValueOrNull(String emitted, Set<String> canonical) {
        if (emitted == null) {
            return; // null is the only permitted non-machine outcome (unknown/absent input).
        }
        assertThat(canonical)
                .as("emitted value must be a canonical machine value, got '%s'", emitted)
                .contains(emitted);
        assertThat(FORBIDDEN_DISPLAY_STRINGS)
                .as("emitted value must never be a Chinese display string, got '%s'", emitted)
                .doesNotContain(emitted);
        assertMachineShape(emitted);
    }

    /** Assert a value is plain lowercase ASCII machine-value shape and contains no CJK character. */
    private static void assertMachineShape(String value) {
        assertThat(MACHINE_VALUE_SHAPE.matcher(value).matches())
                .as("value '%s' must match the lowercase machine-value shape [a-z_]+", value)
                .isTrue();
        assertThat(value.chars().anyMatch(MachineValueOnlyEmissionPropertyTest::isCjk))
                .as("value '%s' must contain no CJK character", value)
                .isFalse();
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    // --- Generators ---------------------------------------------------------------------------

    /**
     * A raw stored value spanning the full input space the emitter must defend against: canonical
     * machine values across all four enums, recognized legacy values, the forbidden Chinese display
     * strings (which must be rejected to {@code null}), blanks/whitespace, and arbitrary unicode noise
     * (including CJK ranges) so display strings other than the known ones are still rejected.
     */
    @Provide
    Arbitrary<String> rawValues() {
        Arbitrary<String> canonical = Arbitraries.of(
                "enabled", "paused", "archived",
                "profit_first", "sales_growth", "rank", "clearance",
                "conservative", "balanced", "aggressive");
        Arbitrary<String> legacy = Arbitraries.of(
                "active", "ACTIVE", " Paused ", "Archived",
                "profit", "launch", "rank_boost", "maximize_sales_at_target",
                "  ENABLED", "Sales_Growth");
        Arbitrary<String> display = Arbitraries.of(FORBIDDEN_DISPLAY_STRINGS.toArray(new String[0]));
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t", "\n");
        Arbitrary<String> nullValue = Arbitraries.just(null);
        Arbitrary<String> asciiNoise = Arbitraries.strings()
                .withCharRange('\u0020', '\u007E').ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> cjkNoise = Arbitraries.strings()
                .withCharRange('\u4E00', '\u9FFF').ofMinLength(1).ofMaxLength(6);
        return Arbitraries.oneOf(
                canonical, legacy, display, blanks, nullValue, asciiNoise, cjkNoise);
    }
}
