package com.adpilot.modules.advertising.hosting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable JSON value object capturing all inputs and derived state at the moment
 * an AI decision is made (Requirement 34).
 *
 * <p>Written once at decision creation onto {@code ai_decisions.decision_snapshot}
 * and (when promoted) the Operation's {@code ai_decision} snapshot column. It is the
 * sole source for Decision_Explanation cards and is never reconstructed from live data.
 *
 * <h3>Captured fields</h3>
 * <ul>
 *   <li>Data cutoff + lookback days</li>
 *   <li>Metric inputs used by the engine</li>
 *   <li>Data Quality gate result</li>
 *   <li>Resolved personality + inheritance chain</li>
 *   <li>Each effective boundary + source level</li>
 *   <li>Risk formula version + score</li>
 *   <li>Rule version, currency, marketplace timezone</li>
 *   <li>Execution Mode, Kill Switch, Shadow Mode flags</li>
 * </ul>
 *
 * <p>This class is intentionally immutable: all fields are final and there are no
 * setters. Collections are wrapped in unmodifiable views to prevent mutation after
 * creation. Serialization/deserialization is handled by Jackson via the annotated
 * constructor.
 *
 * <p>Validates: Requirements 34.1, 34.2, 34.3, 34.4, 37.5, 13.6.</p>
 */
public final class DecisionSnapshot {

    private final LocalDateTime dataCutoff;
    private final int lookbackDays;
    private final Map<String, BigDecimal> metricInputs;
    private final DqGateResult dqGateResult;
    private final String personality;
    private final List<String> inheritanceChain;
    private final List<EffectiveBoundary> effectiveBoundaries;
    private final String riskFormulaVersion;
    private final BigDecimal riskScore;
    private final String ruleVersion;
    private final String currency;
    private final String marketplaceTimezone;
    private final String executionMode;
    private final boolean killSwitchActive;
    private final boolean shadowModeActive;

    @JsonCreator
    public DecisionSnapshot(
            @JsonProperty("dataCutoff") LocalDateTime dataCutoff,
            @JsonProperty("lookbackDays") int lookbackDays,
            @JsonProperty("metricInputs") Map<String, BigDecimal> metricInputs,
            @JsonProperty("dqGateResult") DqGateResult dqGateResult,
            @JsonProperty("personality") String personality,
            @JsonProperty("inheritanceChain") List<String> inheritanceChain,
            @JsonProperty("effectiveBoundaries") List<EffectiveBoundary> effectiveBoundaries,
            @JsonProperty("riskFormulaVersion") String riskFormulaVersion,
            @JsonProperty("riskScore") BigDecimal riskScore,
            @JsonProperty("ruleVersion") String ruleVersion,
            @JsonProperty("currency") String currency,
            @JsonProperty("marketplaceTimezone") String marketplaceTimezone,
            @JsonProperty("executionMode") String executionMode,
            @JsonProperty("killSwitchActive") boolean killSwitchActive,
            @JsonProperty("shadowModeActive") boolean shadowModeActive) {
        this.dataCutoff = dataCutoff;
        this.lookbackDays = lookbackDays;
        this.metricInputs = metricInputs == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(metricInputs);
        this.dqGateResult = dqGateResult;
        this.personality = personality;
        this.inheritanceChain = inheritanceChain == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(inheritanceChain);
        this.effectiveBoundaries = effectiveBoundaries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(effectiveBoundaries);
        this.riskFormulaVersion = riskFormulaVersion;
        this.riskScore = riskScore;
        this.ruleVersion = ruleVersion;
        this.currency = currency;
        this.marketplaceTimezone = marketplaceTimezone;
        this.executionMode = executionMode;
        this.killSwitchActive = killSwitchActive;
        this.shadowModeActive = shadowModeActive;
    }

    // --- Getters (no setters — immutable) ---

    public LocalDateTime getDataCutoff() {
        return dataCutoff;
    }

    public int getLookbackDays() {
        return lookbackDays;
    }

    public Map<String, BigDecimal> getMetricInputs() {
        return metricInputs;
    }

    public DqGateResult getDqGateResult() {
        return dqGateResult;
    }

    public String getPersonality() {
        return personality;
    }

    public List<String> getInheritanceChain() {
        return inheritanceChain;
    }

    public List<EffectiveBoundary> getEffectiveBoundaries() {
        return effectiveBoundaries;
    }

    public String getRiskFormulaVersion() {
        return riskFormulaVersion;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMarketplaceTimezone() {
        return marketplaceTimezone;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public boolean isKillSwitchActive() {
        return killSwitchActive;
    }

    public boolean isShadowModeActive() {
        return shadowModeActive;
    }

    // --- equals / hashCode ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DecisionSnapshot that = (DecisionSnapshot) o;
        return lookbackDays == that.lookbackDays
                && killSwitchActive == that.killSwitchActive
                && shadowModeActive == that.shadowModeActive
                && Objects.equals(dataCutoff, that.dataCutoff)
                && Objects.equals(metricInputs, that.metricInputs)
                && Objects.equals(dqGateResult, that.dqGateResult)
                && Objects.equals(personality, that.personality)
                && Objects.equals(inheritanceChain, that.inheritanceChain)
                && Objects.equals(effectiveBoundaries, that.effectiveBoundaries)
                && Objects.equals(riskFormulaVersion, that.riskFormulaVersion)
                && (riskScore == null ? that.riskScore == null : riskScore.compareTo(that.riskScore) == 0)
                && Objects.equals(ruleVersion, that.ruleVersion)
                && Objects.equals(currency, that.currency)
                && Objects.equals(marketplaceTimezone, that.marketplaceTimezone)
                && Objects.equals(executionMode, that.executionMode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataCutoff, lookbackDays, metricInputs, dqGateResult,
                personality, inheritanceChain, effectiveBoundaries, riskFormulaVersion,
                riskScore, ruleVersion, currency, marketplaceTimezone,
                executionMode, killSwitchActive, shadowModeActive);
    }

    // --- Nested value objects ---

    /**
     * The result of the Data Quality gate evaluation at decision time.
     */
    public static final class DqGateResult {
        private final boolean passed;
        private final String reason;

        @JsonCreator
        public DqGateResult(
                @JsonProperty("passed") boolean passed,
                @JsonProperty("reason") String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        public boolean isPassed() {
            return passed;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DqGateResult that = (DqGateResult) o;
            return passed == that.passed && Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(passed, reason);
        }
    }

    /**
     * An effective boundary value with its source level in the hierarchy.
     */
    public static final class EffectiveBoundary {
        private final String limitName;
        private final String value;
        private final String sourceLevel;

        @JsonCreator
        public EffectiveBoundary(
                @JsonProperty("limitName") String limitName,
                @JsonProperty("value") String value,
                @JsonProperty("sourceLevel") String sourceLevel) {
            this.limitName = limitName;
            this.value = value;
            this.sourceLevel = sourceLevel;
        }

        public String getLimitName() {
            return limitName;
        }

        public String getValue() {
            return value;
        }

        public String getSourceLevel() {
            return sourceLevel;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EffectiveBoundary that = (EffectiveBoundary) o;
            return Objects.equals(limitName, that.limitName)
                    && Objects.equals(value, that.value)
                    && Objects.equals(sourceLevel, that.sourceLevel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(limitName, value, sourceLevel);
        }
    }

    // --- Builder for convenient construction ---

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LocalDateTime dataCutoff;
        private int lookbackDays;
        private Map<String, BigDecimal> metricInputs;
        private DqGateResult dqGateResult;
        private String personality;
        private List<String> inheritanceChain;
        private List<EffectiveBoundary> effectiveBoundaries;
        private String riskFormulaVersion;
        private BigDecimal riskScore;
        private String ruleVersion;
        private String currency;
        private String marketplaceTimezone;
        private String executionMode;
        private boolean killSwitchActive;
        private boolean shadowModeActive;

        private Builder() {}

        public Builder dataCutoff(LocalDateTime dataCutoff) {
            this.dataCutoff = dataCutoff;
            return this;
        }

        public Builder lookbackDays(int lookbackDays) {
            this.lookbackDays = lookbackDays;
            return this;
        }

        public Builder metricInputs(Map<String, BigDecimal> metricInputs) {
            this.metricInputs = metricInputs;
            return this;
        }

        public Builder dqGateResult(DqGateResult dqGateResult) {
            this.dqGateResult = dqGateResult;
            return this;
        }

        public Builder personality(String personality) {
            this.personality = personality;
            return this;
        }

        public Builder inheritanceChain(List<String> inheritanceChain) {
            this.inheritanceChain = inheritanceChain;
            return this;
        }

        public Builder effectiveBoundaries(List<EffectiveBoundary> effectiveBoundaries) {
            this.effectiveBoundaries = effectiveBoundaries;
            return this;
        }

        public Builder riskFormulaVersion(String riskFormulaVersion) {
            this.riskFormulaVersion = riskFormulaVersion;
            return this;
        }

        public Builder riskScore(BigDecimal riskScore) {
            this.riskScore = riskScore;
            return this;
        }

        public Builder ruleVersion(String ruleVersion) {
            this.ruleVersion = ruleVersion;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder marketplaceTimezone(String marketplaceTimezone) {
            this.marketplaceTimezone = marketplaceTimezone;
            return this;
        }

        public Builder executionMode(String executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder killSwitchActive(boolean killSwitchActive) {
            this.killSwitchActive = killSwitchActive;
            return this;
        }

        public Builder shadowModeActive(boolean shadowModeActive) {
            this.shadowModeActive = shadowModeActive;
            return this;
        }

        public DecisionSnapshot build() {
            return new DecisionSnapshot(
                    dataCutoff, lookbackDays, metricInputs, dqGateResult,
                    personality, inheritanceChain, effectiveBoundaries,
                    riskFormulaVersion, riskScore, ruleVersion, currency,
                    marketplaceTimezone, executionMode, killSwitchActive, shadowModeActive);
        }
    }
}
