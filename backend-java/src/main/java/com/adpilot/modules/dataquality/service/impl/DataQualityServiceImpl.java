package com.adpilot.modules.dataquality.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.dataquality.entity.DataQualityIssueEntity;
import com.adpilot.modules.dataquality.mapper.DataQualityIssueMapper;
import com.adpilot.modules.dataquality.service.DataQualityService;
import com.adpilot.modules.dataquality.vo.DataQualityIssueVo;
import com.adpilot.modules.dataquality.vo.QualityCheckResultVo;
import com.adpilot.modules.importcenter.entity.ImportJobEntity;
import com.adpilot.modules.importcenter.entity.RawSearchTermReportEntity;
import com.adpilot.modules.importcenter.mapper.ImportJobMapper;
import com.adpilot.modules.importcenter.mapper.RawSearchTermReportMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityServiceImpl implements DataQualityService {

    private final DataQualityIssueMapper dataQualityIssueMapper;
    private final ImportJobMapper importJobMapper;
    private final RawSearchTermReportMapper rawSearchTermReportMapper;
    private final CampaignMapper campaignMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public List<DataQualityIssueVo> listIssues(String storeId, String status, String severity) {
        LambdaQueryWrapper<DataQualityIssueEntity> wrapper = new LambdaQueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq(DataQualityIssueEntity::getStoreId, UUID.fromString(storeId));
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(DataQualityIssueEntity::getStatus, status);
        }
        if (severity != null && !severity.isEmpty()) {
            wrapper.eq(DataQualityIssueEntity::getSeverity, severity);
        }
        wrapper.orderByDesc(DataQualityIssueEntity::getCreatedAt);

        List<DataQualityIssueEntity> entities = dataQualityIssueMapper.selectList(wrapper);
        return entities.stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public QualityCheckResultVo runQualityCheck(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            throw new BusinessException("MISSING_STORE", "请选择店铺");
        }

        UUID storeUuid = UUID.fromString(storeId);
        int checksPerformed = 0;
        int issuesFound = 0;
        Map<String, Integer> issuesByCategory = new LinkedHashMap<>();

        // Check 1: Missing report dates in raw imports
        checksPerformed++;
        LambdaQueryWrapper<RawSearchTermReportEntity> missingDateWrapper = new LambdaQueryWrapper<>();
        missingDateWrapper.eq(RawSearchTermReportEntity::getStoreId, storeUuid)
                .isNull(RawSearchTermReportEntity::getReportDate);
        Long missingDateCount = rawSearchTermReportMapper.selectCount(missingDateWrapper);
        if (missingDateCount != null && missingDateCount > 0) {
            createIssue(storeUuid, null, "warning", "missing_date",
                    "存在缺少报告日期的导入数据",
                    "共有 " + missingDateCount + " 条记录缺少report_date字段，这会影响按日期维度的数据分析准确性。",
                    "raw_search_term_report", null);
            issuesFound++;
            issuesByCategory.merge("missing_date", 1, Integer::sum);
        }

        // Check 2: Unmatched campaigns (raw data references campaigns not in campaigns table)
        checksPerformed++;
        LambdaQueryWrapper<RawSearchTermReportEntity> rawWrapper = new LambdaQueryWrapper<>();
        rawWrapper.eq(RawSearchTermReportEntity::getStoreId, storeUuid)
                .isNotNull(RawSearchTermReportEntity::getCampaignName)
                .groupBy(RawSearchTermReportEntity::getCampaignName);
        rawWrapper.select(RawSearchTermReportEntity::getCampaignName);
        List<RawSearchTermReportEntity> distinctCampaigns = rawSearchTermReportMapper.selectList(rawWrapper);

        Set<String> existingCampaignNames = new HashSet<>();
        LambdaQueryWrapper<CampaignEntity> campaignWrapper = new LambdaQueryWrapper<>();
        campaignWrapper.eq(CampaignEntity::getStoreId, storeUuid);
        List<CampaignEntity> campaigns = campaignMapper.selectList(campaignWrapper);
        campaigns.forEach(c -> existingCampaignNames.add(c.getName()));

        int unmatchedCount = 0;
        for (RawSearchTermReportEntity raw : distinctCampaigns) {
            if (raw.getCampaignName() != null && !existingCampaignNames.contains(raw.getCampaignName())) {
                unmatchedCount++;
            }
        }
        if (unmatchedCount > 0) {
            createIssue(storeUuid, null, "warning", "unmatched_campaign",
                    "存在未匹配的广告活动",
                    "共有 " + unmatchedCount + " 个在导入数据中出现的广告活动名称，在系统广告活动中未找到匹配项。",
                    "campaign", null);
            issuesFound++;
            issuesByCategory.merge("unmatched_campaign", 1, Integer::sum);
        }

        // Check 3: Invalid metrics (negative values)
        checksPerformed++;
        LambdaQueryWrapper<RawSearchTermReportEntity> negativeWrapper = new LambdaQueryWrapper<>();
        negativeWrapper.eq(RawSearchTermReportEntity::getStoreId, storeUuid)
                .and(w -> w
                        .lt(RawSearchTermReportEntity::getImpressions, 0)
                        .or()
                        .lt(RawSearchTermReportEntity::getClicks, 0)
                );
        Long negativeCount = rawSearchTermReportMapper.selectCount(negativeWrapper);
        if (negativeCount != null && negativeCount > 0) {
            createIssue(storeUuid, null, "error", "invalid_metrics",
                    "存在无效的指标数据",
                    "共有 " + negativeCount + " 条记录的impressions或clicks为负值，请检查数据源。",
                    "raw_search_term_report", null);
            issuesFound++;
            issuesByCategory.merge("invalid_metrics", 1, Integer::sum);
        }

        // Check 4: Clicks > Impressions (logically impossible)
        checksPerformed++;
        LambdaQueryWrapper<RawSearchTermReportEntity> logicWrapper = new LambdaQueryWrapper<>();
        logicWrapper.eq(RawSearchTermReportEntity::getStoreId, storeUuid)
                .apply("clicks > impressions");
        Long logicErrorCount = rawSearchTermReportMapper.selectCount(logicWrapper);
        if (logicErrorCount != null && logicErrorCount > 0) {
            createIssue(storeUuid, null, "error", "impossible_metrics",
                    "存在逻辑错误的指标数据",
                    "共有 " + logicErrorCount + " 条记录的clicks大于impressions，数据可能存在异常。",
                    "raw_search_term_report", null);
            issuesFound++;
            issuesByCategory.merge("impossible_metrics", 1, Integer::sum);
        }

        // Check 5: Duplicate imports (same data imported multiple times)
        checksPerformed++;
        LambdaQueryWrapper<ImportJobEntity> importWrapper = new LambdaQueryWrapper<>();
        importWrapper.eq(ImportJobEntity::getStoreId, storeUuid)
                .eq(ImportJobEntity::getStatus, "committed");
        List<ImportJobEntity> committedImports = importJobMapper.selectList(importWrapper);
        Map<String, Long> importsByFile = committedImports.stream()
                .filter(j -> j.getFileName() != null)
                .collect(Collectors.groupingBy(ImportJobEntity::getFileName, Collectors.counting()));

        for (Map.Entry<String, Long> entry : importsByFile.entrySet()) {
            if (entry.getValue() > 1) {
                createIssue(storeUuid, null, "warning", "duplicate_import",
                        "检测到重复导入",
                        "文件 \"" + entry.getKey() + "\" 被提交了 " + entry.getValue() + " 次，可能存在重复数据。",
                        "import_job", null);
                issuesFound++;
                issuesByCategory.merge("duplicate_import", 1, Integer::sum);
            }
        }

        // Check 6: Zero-spend records with sales (data inconsistency)
        checksPerformed++;
        LambdaQueryWrapper<RawSearchTermReportEntity> zeroSpendWrapper = new LambdaQueryWrapper<>();
        zeroSpendWrapper.eq(RawSearchTermReportEntity::getStoreId, storeUuid)
                .eq(RawSearchTermReportEntity::getSpend, BigDecimal.ZERO)
                .gt(RawSearchTermReportEntity::getSales, BigDecimal.ZERO)
                .gt(RawSearchTermReportEntity::getOrders, 0);
        Long zeroSpendCount = rawSearchTermReportMapper.selectCount(zeroSpendWrapper);
        if (zeroSpendCount != null && zeroSpendCount > 0) {
            createIssue(storeUuid, null, "warning", "zero_spend_sales",
                    "零花费但有销售额的数据",
                    "共有 " + zeroSpendCount + " 条记录花费为0但有销售额和订单，可能是数据缺失。",
                    "raw_search_term_report", null);
            issuesFound++;
            issuesByCategory.merge("zero_spend_sales", 1, Integer::sum);
        }

        String summary = "完成 " + checksPerformed + " 项检查，发现 " + issuesFound + " 个问题";
        if (issuesFound == 0) {
            summary = "完成 " + checksPerformed + " 项检查，数据质量良好，未发现问题";
        }

        log.info("Data quality check completed for store {}: {}", storeId, summary);

        return QualityCheckResultVo.builder()
                .checksPerformed(checksPerformed)
                .issuesFound(issuesFound)
                .issuesByCategory(issuesByCategory)
                .summary(summary)
                .build();
    }

    @Override
    @Transactional
    public DataQualityIssueVo resolveIssue(String id, String userId) {
        DataQualityIssueEntity entity = dataQualityIssueMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("ISSUE_NOT_FOUND", "质量问题不存在: " + id);
        }

        entity.setStatus("resolved");
        entity.setUpdatedAt(LocalDateTime.now());
        dataQualityIssueMapper.updateById(entity);

        auditLogService.createLog(
                userId != null ? UUID.fromString(userId) : null,
                null, "RESOLVE", "data_quality_issue", entity.getId(),
                Map.of("issueType", entity.getIssueType(), "title", entity.getTitle())
        );

        log.info("Data quality issue resolved: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public DataQualityIssueVo ignoreIssue(String id, String userId) {
        DataQualityIssueEntity entity = dataQualityIssueMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("ISSUE_NOT_FOUND", "质量问题不存在: " + id);
        }

        entity.setStatus("ignored");
        entity.setUpdatedAt(LocalDateTime.now());
        dataQualityIssueMapper.updateById(entity);

        auditLogService.createLog(
                userId != null ? UUID.fromString(userId) : null,
                null, "IGNORE", "data_quality_issue", entity.getId(),
                Map.of("issueType", entity.getIssueType(), "title", entity.getTitle())
        );

        log.info("Data quality issue ignored: id={}", id);
        return toVo(entity);
    }

    // ---- Private helpers ----

    private void createIssue(UUID storeId, UUID importJobId, String severity, String issueType,
                             String title, String description, String relatedEntityType, UUID relatedEntityId) {
        // Avoid creating duplicate open issues of the same type for the same store
        LambdaQueryWrapper<DataQualityIssueEntity> dupWrapper = new LambdaQueryWrapper<>();
        dupWrapper.eq(DataQualityIssueEntity::getStoreId, storeId)
                .eq(DataQualityIssueEntity::getIssueType, issueType)
                .eq(DataQualityIssueEntity::getStatus, "open");
        if (importJobId != null) {
            dupWrapper.eq(DataQualityIssueEntity::getImportJobId, importJobId);
        }
        Long existingCount = dataQualityIssueMapper.selectCount(dupWrapper);
        if (existingCount != null && existingCount > 0) {
            log.debug("Skipping duplicate quality issue: type={}, store={}", issueType, storeId);
            return;
        }

        DataQualityIssueEntity issue = DataQualityIssueEntity.builder()
                .storeId(storeId)
                .importJobId(importJobId)
                .severity(severity)
                .issueType(issueType)
                .title(title)
                .description(description)
                .relatedEntityType(relatedEntityType)
                .relatedEntityId(relatedEntityId)
                .status("open")
                .build();
        dataQualityIssueMapper.insert(issue);
    }

    private DataQualityIssueVo toVo(DataQualityIssueEntity entity) {
        return DataQualityIssueVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .importJobId(entity.getImportJobId() != null ? entity.getImportJobId().toString() : null)
                .severity(entity.getSeverity())
                .issueType(entity.getIssueType())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .relatedEntityType(entity.getRelatedEntityType())
                .relatedEntityId(entity.getRelatedEntityId() != null ? entity.getRelatedEntityId().toString() : null)
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
