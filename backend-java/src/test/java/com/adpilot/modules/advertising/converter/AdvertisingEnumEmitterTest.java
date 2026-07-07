package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.advertising.vo.GoalVo;
import com.adpilot.modules.advertising.vo.KeywordVo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the advertising response boundary emits the four enumerated fields — AI_Hosting_Status,
 * AI_Personality, Optimization_Goal, and Object_Status — as {@code Machine_Value_Enum} values only,
 * and never as a Chinese display string (Requirements 14.7, 48.4; Property 34).
 */
class AdvertisingEnumEmitterTest {

    // ---- AI_Hosting_Status ----------------------------------------------------------------------

    @Test
    void aiHostingStatusIsAlwaysAMachineValue() {
        assertThat(AdvertisingEnumEmitter.aiHostingStatus(true)).isEqualTo("hosted");
        assertThat(AdvertisingEnumEmitter.aiHostingStatus(false)).isEqualTo("not_hosted");
    }

    // ---- Object_Status --------------------------------------------------------------------------

    @Test
    void objectStatusNormalizesCanonicalAndLegacyValues() {
        assertThat(AdvertisingEnumEmitter.objectStatus("enabled")).isEqualTo("enabled");
        assertThat(AdvertisingEnumEmitter.objectStatus("paused")).isEqualTo("paused");
        assertThat(AdvertisingEnumEmitter.objectStatus("archived")).isEqualTo("archived");
        // legacy active normalizes to enabled; case/whitespace tolerant
        assertThat(AdvertisingEnumEmitter.objectStatus("active")).isEqualTo("enabled");
        assertThat(AdvertisingEnumEmitter.objectStatus("  ENABLED ")).isEqualTo("enabled");
    }

    @Test
    void objectStatusNeverLeaksADisplayStringOrUnknownValue() {
        assertThat(AdvertisingEnumEmitter.objectStatus(null)).isNull();
        assertThat(AdvertisingEnumEmitter.objectStatus("")).isNull();
        assertThat(AdvertisingEnumEmitter.objectStatus("已启用")).isNull();
        assertThat(AdvertisingEnumEmitter.objectStatus("暂停")).isNull();
    }

    // ---- Optimization_Goal ----------------------------------------------------------------------

    @Test
    void optimizationGoalNormalizesCanonicalAndLegacyValues() {
        assertThat(AdvertisingEnumEmitter.optimizationGoal("profit_first")).isEqualTo("profit_first");
        assertThat(AdvertisingEnumEmitter.optimizationGoal("sales_growth")).isEqualTo("sales_growth");
        assertThat(AdvertisingEnumEmitter.optimizationGoal("rank")).isEqualTo("rank");
        assertThat(AdvertisingEnumEmitter.optimizationGoal("clearance")).isEqualTo("clearance");
        // legacy hosting-goal / goal-type values map to the new enum
        assertThat(AdvertisingEnumEmitter.optimizationGoal("maximize_sales_at_target")).isEqualTo("sales_growth");
        assertThat(AdvertisingEnumEmitter.optimizationGoal("profit")).isEqualTo("profit_first");
        assertThat(AdvertisingEnumEmitter.optimizationGoal("rank_boost")).isEqualTo("rank");
    }

    @Test
    void optimizationGoalNeverLeaksADisplayStringOrUnmappableValue() {
        assertThat(AdvertisingEnumEmitter.optimizationGoal(null)).isNull();
        assertThat(AdvertisingEnumEmitter.optimizationGoal("利润优先")).isNull();
        // ambiguous legacy targeting strategies are not auto-mapped
        assertThat(AdvertisingEnumEmitter.optimizationGoal("brand_defense")).isNull();
    }

    // ---- AI_Personality -------------------------------------------------------------------------

    @Test
    void aiPersonalityNormalizesCanonicalValues() {
        assertThat(AdvertisingEnumEmitter.aiPersonality("conservative")).isEqualTo("conservative");
        assertThat(AdvertisingEnumEmitter.aiPersonality("balanced")).isEqualTo("balanced");
        assertThat(AdvertisingEnumEmitter.aiPersonality("aggressive")).isEqualTo("aggressive");
        assertThat(AdvertisingEnumEmitter.aiPersonality(" AGGRESSIVE ")).isEqualTo("aggressive");
    }

    @Test
    void aiPersonalityNeverLeaksADisplayStringOrUnknownValue() {
        assertThat(AdvertisingEnumEmitter.aiPersonality(null)).isNull();
        assertThat(AdvertisingEnumEmitter.aiPersonality("常规型")).isNull();
        assertThat(AdvertisingEnumEmitter.aiPersonality("稳健型")).isNull();
    }

    // ---- Converter integration ------------------------------------------------------------------

    @Test
    void campaignConverterEmitsMachineValueEnums() {
        CampaignEntity entity = CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("c")
                .status("active")                       // legacy Object_Status
                .hostingEnabled(true)                   // AI_Hosting_Status
                .hostingGoal("maximize_sales_at_target") // legacy Optimization_Goal
                .campaignPersonality("aggressive")      // AI_Personality
                .build();

        CampaignVo vo = CampaignConverter.toVo(entity);

        assertThat(vo.getStatus()).isEqualTo("enabled");
        assertThat(vo.getAiHostingStatus()).isEqualTo("hosted");
        assertThat(vo.getOptimizationGoal()).isEqualTo("sales_growth");
        assertThat(vo.getCampaignPersonality()).isEqualTo("aggressive");
        // legacy hosting goal field is preserved as-is for back-compat; it is not a Chinese string
        assertThat(vo.getHostingGoal()).isEqualTo("maximize_sales_at_target");
    }

    @Test
    void campaignConverterEmitsNotHostedWhenHostingDisabled() {
        CampaignEntity entity = CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("c")
                .status("paused")
                .hostingEnabled(false)
                .build();

        CampaignVo vo = CampaignConverter.toVo(entity);

        assertThat(vo.getAiHostingStatus()).isEqualTo("not_hosted");
        assertThat(vo.getStatus()).isEqualTo("paused");
        assertThat(vo.getOptimizationGoal()).isNull();
    }

    @Test
    void goalConverterEmitsMachineValueEnums() {
        GoalEntity entity = GoalEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("g")
                .type("profit")              // legacy goal type -> Optimization_Goal
                .riskPreference("balanced")  // AI_Personality
                .build();

        GoalVo vo = GoalConverter.toVo(entity);

        assertThat(vo.getRiskPreference()).isEqualTo("balanced");
        assertThat(vo.getOptimizationGoal()).isEqualTo("profit_first");
    }

    @Test
    void keywordConverterNormalizesObjectStatus() {
        KeywordEntity entity = KeywordEntity.builder()
                .id(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .adGroupId(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .keywordText("kw")
                .status("active")
                .bid(new BigDecimal("1.00"))
                .build();

        KeywordVo vo = KeywordConverter.toVo(entity);

        assertThat(vo.getStatus()).isEqualTo("enabled");
    }
}
