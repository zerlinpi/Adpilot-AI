package com.adpilot.modules.advertising.service.impl;

import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.service.CampaignBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignBuilderServiceImpl implements CampaignBuilderService {

    private final CampaignMapper campaignMapper;
    private final AdGroupMapper adGroupMapper;

    @Override
    @Transactional
    public List<String> buildCampaignsFromGoal(GoalEntity goal, String storeId) {
        String goalType = goal.getType();
        List<CampaignTemplate> templates = getCampaignTemplates(goalType);

        List<String> campaignIds = new ArrayList<>();
        UUID storeUuid = UUID.fromString(storeId);

        for (CampaignTemplate template : templates) {
            // Create campaign
            CampaignEntity campaign = CampaignEntity.builder()
                    .goalId(goal.getId())
                    .storeId(storeUuid)
                    .name(goal.getName() + " - " + template.name)
                    .campaignType(template.campaignType)
                    .portfolio(template.portfolio)
                    .status("enabled")
                    .budget(calculateBudget(goal.getDailyBudget(), template.budgetPercentage))
                    .budgetType("daily")
                    .targetingType(template.targetingType)
                    .state("enabled")
                    .createdBy(goal.getCreatedBy())
                    .updatedBy(goal.getCreatedBy())
                    .build();

            campaignMapper.insert(campaign);
            campaignIds.add(campaign.getId().toString());

            // Create default ad group for the campaign
            AdGroupEntity adGroup = AdGroupEntity.builder()
                    .campaignId(campaign.getId())
                    .storeId(storeUuid)
                    .name(template.name + " - Ad Group")
                    .status("enabled")
                    .defaultBid(goal.getMaxBid() != null ? goal.getMaxBid() : BigDecimal.ZERO)
                    .createdBy(goal.getCreatedBy())
                    .updatedBy(goal.getCreatedBy())
                    .build();

            adGroupMapper.insert(adGroup);

            log.info("Created campaign '{}' with ad group for goal {}", template.name, goal.getId());
        }

        return campaignIds;
    }

    private List<CampaignTemplate> getCampaignTemplates(String goalType) {
        List<CampaignTemplate> templates = new ArrayList<>();

        switch (goalType) {
            case "launch":
                templates.add(new CampaignTemplate("Auto Discovery", "sp-auto", "auto", 0.40, "auto"));
                templates.add(new CampaignTemplate("Broad Keyword", "sp-keyword", "keyword", 0.30, "manual"));
                templates.add(new CampaignTemplate("Category PAT", "sp-pat", "product", 0.20, "manual"));
                templates.add(new CampaignTemplate("Exact Winner", "sp-keyword", "keyword", 0.10, "manual"));
                break;

            case "profit":
                templates.add(new CampaignTemplate("Exact Keyword", "sp-keyword", "keyword", 0.40, "manual"));
                templates.add(new CampaignTemplate("Phrase Keyword", "sp-keyword", "keyword", 0.25, "manual"));
                templates.add(new CampaignTemplate("PAT", "sp-pat", "product", 0.20, "manual"));
                templates.add(new CampaignTemplate("Auto Waste Control", "sp-auto", "auto", 0.15, "auto"));
                break;

            case "brand_defense":
                templates.add(new CampaignTemplate("Branded Exact", "sp-keyword", "keyword", 0.40, "manual"));
                templates.add(new CampaignTemplate("Branded Phrase", "sp-keyword", "keyword", 0.35, "manual"));
                templates.add(new CampaignTemplate("SB Defense", "sb-brand", "brand", 0.25, "manual"));
                break;

            case "competitor":
                templates.add(new CampaignTemplate("Competitor Keyword", "sp-keyword", "keyword", 0.40, "manual"));
                templates.add(new CampaignTemplate("Competitor ASIN PAT", "sp-pat", "product", 0.35, "manual"));
                templates.add(new CampaignTemplate("Competitor Brand", "sp-keyword", "keyword", 0.25, "manual"));
                break;

            case "category":
                templates.add(new CampaignTemplate("Category Keyword", "sp-keyword", "keyword", 0.40, "manual"));
                templates.add(new CampaignTemplate("Category PAT", "sp-pat", "product", 0.35, "manual"));
                templates.add(new CampaignTemplate("Auto Discovery", "sp-auto", "auto", 0.25, "auto"));
                break;

            case "clearance":
                templates.add(new CampaignTemplate("Auto Clearance", "sp-auto", "auto", 0.50, "auto"));
                templates.add(new CampaignTemplate("Manual Clearance", "sp-keyword", "keyword", 0.50, "manual"));
                break;

            case "rank_boost":
                templates.add(new CampaignTemplate("Exact Rank Push", "sp-keyword", "keyword", 0.60, "manual"));
                templates.add(new CampaignTemplate("Category Rank Push", "sp-keyword", "keyword", 0.40, "manual"));
                break;

            default:
                // Default to a simple auto + manual setup
                templates.add(new CampaignTemplate("Auto Campaign", "sp-auto", "auto", 0.50, "auto"));
                templates.add(new CampaignTemplate("Manual Campaign", "sp-keyword", "keyword", 0.50, "manual"));
                break;
        }

        return templates;
    }

    private BigDecimal calculateBudget(BigDecimal dailyBudget, double percentage) {
        if (dailyBudget == null) return BigDecimal.ZERO;
        return dailyBudget.multiply(BigDecimal.valueOf(percentage));
    }

    private static class CampaignTemplate {
        final String name;
        final String campaignType;
        final String portfolio;
        final double budgetPercentage;
        final String targetingType;

        CampaignTemplate(String name, String campaignType, String portfolio, double budgetPercentage, String targetingType) {
            this.name = name;
            this.campaignType = campaignType;
            this.portfolio = portfolio;
            this.budgetPercentage = budgetPercentage;
            this.targetingType = targetingType;
        }
    }
}
