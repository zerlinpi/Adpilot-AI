package com.adpilot.modules.advertising.hosting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExecutionModeResolverImpl} pure resolution logic (Req 7.2).
 *
 * <p>The pure {@code resolve(String, String, String)} method is tested directly
 * without database access, verifying the campaign → goal → store → default
 * inheritance chain.
 */
class ExecutionModeResolverTest {

    private ExecutionModeResolverImpl resolver;

    @BeforeEach
    void setUp() {
        // The pure resolve() method needs no mapper or objectMapper, but we
        // construct a real instance for completeness.
        resolver = new ExecutionModeResolverImpl(null, new ObjectMapper());
    }

    @Nested
    @DisplayName("ExecutionMode.parse")
    class ParseTests {

        @Test
        @DisplayName("parses all valid values case-insensitively")
        void parsesValidValues() {
            assertThat(ExecutionMode.parse("observe_only")).isEqualTo(ExecutionMode.OBSERVE_ONLY);
            assertThat(ExecutionMode.parse("recommend_only")).isEqualTo(ExecutionMode.RECOMMEND_ONLY);
            assertThat(ExecutionMode.parse("approval_required")).isEqualTo(ExecutionMode.APPROVAL_REQUIRED);
            assertThat(ExecutionMode.parse("auto_execute")).isEqualTo(ExecutionMode.AUTO_EXECUTE);
            assertThat(ExecutionMode.parse("AUTO_EXECUTE")).isEqualTo(ExecutionMode.AUTO_EXECUTE);
            assertThat(ExecutionMode.parse("  Observe_Only  ")).isEqualTo(ExecutionMode.OBSERVE_ONLY);
        }

        @Test
        @DisplayName("returns null for null, blank, or unrecognized values")
        void returnsNullForInvalid() {
            assertThat(ExecutionMode.parse(null)).isNull();
            assertThat(ExecutionMode.parse("")).isNull();
            assertThat(ExecutionMode.parse("   ")).isNull();
            assertThat(ExecutionMode.parse("unknown_mode")).isNull();
            assertThat(ExecutionMode.parse("auto")).isNull();
        }
    }

    @Nested
    @DisplayName("resolve() pure precedence")
    class ResolvePureTests {

        @Test
        @DisplayName("campaign override wins over goal and store")
        void campaignWins() {
            ExecutionMode result = resolver.resolve("auto_execute", "approval_required", "observe_only");
            assertThat(result).isEqualTo(ExecutionMode.AUTO_EXECUTE);
        }

        @Test
        @DisplayName("goal wins when campaign is not set")
        void goalWinsWhenNoCampaign() {
            ExecutionMode result = resolver.resolve(null, "approval_required", "auto_execute");
            assertThat(result).isEqualTo(ExecutionMode.APPROVAL_REQUIRED);
        }

        @Test
        @DisplayName("store wins when campaign and goal are not set")
        void storeWinsWhenNoCampaignOrGoal() {
            ExecutionMode result = resolver.resolve(null, null, "recommend_only");
            assertThat(result).isEqualTo(ExecutionMode.RECOMMEND_ONLY);
        }

        @Test
        @DisplayName("defaults to OBSERVE_ONLY when no level is configured")
        void defaultsToObserveOnly() {
            ExecutionMode result = resolver.resolve(null, null, null);
            assertThat(result).isEqualTo(ExecutionMode.OBSERVE_ONLY);
        }

        @Test
        @DisplayName("defaults to OBSERVE_ONLY when all values are blank")
        void defaultsWhenAllBlank() {
            ExecutionMode result = resolver.resolve("", "   ", "");
            assertThat(result).isEqualTo(ExecutionMode.OBSERVE_ONLY);
        }

        @Test
        @DisplayName("skips unrecognized campaign value and uses goal")
        void skipsUnrecognizedCampaign() {
            ExecutionMode result = resolver.resolve("invalid", "auto_execute", "observe_only");
            assertThat(result).isEqualTo(ExecutionMode.AUTO_EXECUTE);
        }

        @Test
        @DisplayName("skips unrecognized campaign and goal, uses store")
        void skipsUnrecognizedCampaignAndGoal() {
            ExecutionMode result = resolver.resolve("bad", "worse", "approval_required");
            assertThat(result).isEqualTo(ExecutionMode.APPROVAL_REQUIRED);
        }

        @Test
        @DisplayName("all unrecognized values fall through to default")
        void allUnrecognizedFallToDefault() {
            ExecutionMode result = resolver.resolve("xyz", "abc", "123");
            assertThat(result).isEqualTo(ExecutionMode.OBSERVE_ONLY);
        }
    }

    @Nested
    @DisplayName("ExecutionMode enum")
    class EnumTests {

        @Test
        @DisplayName("DEFAULT is OBSERVE_ONLY")
        void defaultIsObserveOnly() {
            assertThat(ExecutionMode.DEFAULT).isEqualTo(ExecutionMode.OBSERVE_ONLY);
        }

        @Test
        @DisplayName("value() returns the canonical wire value")
        void valueReturnsCanonical() {
            assertThat(ExecutionMode.OBSERVE_ONLY.value()).isEqualTo("observe_only");
            assertThat(ExecutionMode.RECOMMEND_ONLY.value()).isEqualTo("recommend_only");
            assertThat(ExecutionMode.APPROVAL_REQUIRED.value()).isEqualTo("approval_required");
            assertThat(ExecutionMode.AUTO_EXECUTE.value()).isEqualTo("auto_execute");
        }
    }
}
