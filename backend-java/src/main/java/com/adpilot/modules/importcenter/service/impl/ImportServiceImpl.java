package com.adpilot.modules.importcenter.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.importcenter.dto.ImportMappingRequest;
import com.adpilot.modules.importcenter.dto.ImportUploadRequest;
import com.adpilot.modules.importcenter.entity.ImportJobEntity;
import com.adpilot.modules.importcenter.entity.ImportRowErrorEntity;
import com.adpilot.modules.importcenter.entity.RawSearchTermReportEntity;
import com.adpilot.modules.importcenter.mapper.ImportJobMapper;
import com.adpilot.modules.importcenter.mapper.ImportRowErrorMapper;
import com.adpilot.modules.importcenter.mapper.RawSearchTermReportMapper;
import com.adpilot.modules.importcenter.service.ImportService;
import com.adpilot.modules.importcenter.vo.ImportJobVo;
import com.adpilot.modules.importcenter.vo.ImportPreviewVo;
import com.adpilot.modules.importcenter.vo.ImportRowErrorVo;
import com.adpilot.modules.importcenter.vo.ImportValidationVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private final ImportJobMapper importJobMapper;
    private final ImportRowErrorMapper importRowErrorMapper;
    private final RawSearchTermReportMapper rawSearchTermReportMapper;
    private final CampaignMapper campaignMapper;
    private final AdGroupMapper adGroupMapper;
    private final SearchTermMapper searchTermMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceReferenceService marketplaceReferenceService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PREVIEW_LIMIT = 20;

    /** Store-only scope target for import jobs (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    /** Reject an import job whose store is outside the caller's read scope. */
    private void assertJobReadable(ImportJobEntity job) {
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(job, user);
        }
    }

    /** Reject an import job whose store is outside the caller's write scope. */
    private void assertJobWritable(ImportJobEntity job) {
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(job, user);
        }
    }

    @Override
    public PageResponse<ImportJobVo> listImports(String storeId, int page, int pageSize) {
        Page<ImportJobEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ImportJobEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", UUID.fromString(storeId).toString());
        }
        // Store-scope the listing so a caller only sees import jobs for stores within
        // their effective data scope (empty scope short-circuits to no rows).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<ImportJobEntity> result = importJobMapper.selectPage(pageParam, wrapper);
        List<ImportJobVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public ImportJobVo uploadImport(ImportUploadRequest request, String userId) {
        if (request.getCsvContent() == null || request.getCsvContent().isBlank()) {
            throw new BusinessException("EMPTY_FILE", "上传文件内容不能为空");
        }
        if (request.getStoreId() == null || request.getStoreId().isBlank()) {
            throw new BusinessException("MISSING_STORE", "请选择店铺");
        }
        if (request.getReportType() == null || request.getReportType().isBlank()) {
            throw new BusinessException("MISSING_REPORT_TYPE", "请选择报告类型");
        }

        // Create import job
        ImportJobEntity job = ImportJobEntity.builder()
                .storeId(UUID.fromString(request.getStoreId()))
                .marketplaceId(request.getMarketplaceId() != null ? UUID.fromString(request.getMarketplaceId()) : null)
                .reportType(request.getReportType())
                .fileName(request.getFileName())
                .fileSize((long) request.getCsvContent().getBytes().length)
                .status("uploaded")
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .build();
        importJobMapper.insert(job);

        // Parse CSV and store raw rows
        String[] lines = request.getCsvContent().split("\\r?\\n");
        if (lines.length < 2) {
            job.setStatus("failed");
            job.setErrorMessage("CSV文件至少需要包含表头和一行数据");
            importJobMapper.updateById(job);
            throw new BusinessException("INVALID_CSV", "CSV文件至少需要包含表头和一行数据");
        }

        String[] headers = parseCsvLine(lines[0]);
        int totalRows = 0;
        int validRows = 0;
        int invalidRows = 0;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            totalRows++;
            String[] values = parseCsvLine(lines[i]);
            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int j = 0; j < headers.length && j < values.length; j++) {
                rowMap.put(headers[j].trim(), values[j].trim());
            }

            try {
                String rawDataJson = objectMapper.writeValueAsString(rowMap);
                RawSearchTermReportEntity rawRow = RawSearchTermReportEntity.builder()
                        .importJobId(job.getId())
                        .storeId(job.getStoreId())
                        .marketplaceId(job.getMarketplaceId())
                        .campaignName(getValue(rowMap, "Campaign Name", "campaign_name"))
                        .adGroupName(getValue(rowMap, "Ad Group Name", "ad_group_name"))
                        .targeting(getValue(rowMap, "Targeting", "targeting"))
                        .matchType(getValue(rowMap, "Match Type", "match_type"))
                        .customerSearchTerm(getValue(rowMap, "Customer Search Term", "customer_search_term", "search_term"))
                        .impressions(parseInteger(getValue(rowMap, "Impressions", "impressions")))
                        .clicks(parseInteger(getValue(rowMap, "Clicks", "clicks")))
                        .spend(parseBigDecimal(getValue(rowMap, "Spend", "spend")))
                        .sales(parseBigDecimal(getValue(rowMap, "Sales", "sales")))
                        .orders(parseInteger(getValue(rowMap, "Orders", "orders")))
                        .reportDate(parseLocalDate(getValue(rowMap, "Date", "Report Date", "report_date")))
                        .rawData(rawDataJson)
                        .build();
                rawSearchTermReportMapper.insert(rawRow);
                validRows++;
            } catch (Exception e) {
                invalidRows++;
                log.warn("Failed to parse row {}: {}", i, e.getMessage());
                saveRowError(job.getId(), i, lines[i], "PARSE_ERROR", "数据解析失败: " + e.getMessage());
            }
        }

        job.setTotalRows(totalRows);
        job.setValidRows(validRows);
        job.setInvalidRows(invalidRows);
        job.setStatus("parsed");
        importJobMapper.updateById(job);

        // Audit log
        auditLogService.createLog(
                userId != null ? UUID.fromString(userId) : null,
                null, "CREATE", "import_job", job.getId(),
                Map.of("reportType", request.getReportType(), "totalRows", totalRows)
        );

        log.info("Import job created: id={}, totalRows={}, validRows={}", job.getId(), totalRows, validRows);
        return toVo(job);
    }

    @Override
    public ImportJobVo getImport(String id) {
        ImportJobEntity entity = importJobMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入任务不存在: " + id);
        }
        assertJobReadable(entity);
        return toVo(entity);
    }

    @Override
    public ImportPreviewVo previewImport(String id) {
        ImportJobEntity job = importJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入任务不存在: " + id);
        }
        assertJobReadable(job);

        LambdaQueryWrapper<RawSearchTermReportEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RawSearchTermReportEntity::getImportJobId, job.getId())
                .last("LIMIT " + PREVIEW_LIMIT);

        List<RawSearchTermReportEntity> rawRows = rawSearchTermReportMapper.selectList(wrapper);

        List<Map<String, String>> rows = rawRows.stream()
                .map(row -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("campaign_name", nvl(row.getCampaignName()));
                    map.put("ad_group_name", nvl(row.getAdGroupName()));
                    map.put("targeting", nvl(row.getTargeting()));
                    map.put("match_type", nvl(row.getMatchType()));
                    map.put("customer_search_term", nvl(row.getCustomerSearchTerm()));
                    map.put("impressions", row.getImpressions() != null ? row.getImpressions().toString() : "0");
                    map.put("clicks", row.getClicks() != null ? row.getClicks().toString() : "0");
                    map.put("spend", row.getSpend() != null ? row.getSpend().toPlainString() : "0");
                    map.put("sales", row.getSales() != null ? row.getSales().toPlainString() : "0");
                    map.put("orders", row.getOrders() != null ? row.getOrders().toString() : "0");
                    map.put("report_date", row.getReportDate() != null ? row.getReportDate().toString() : "");
                    return map;
                })
                .collect(Collectors.toList());

        List<String> headers = Arrays.asList(
                "campaign_name", "ad_group_name", "targeting", "match_type",
                "customer_search_term", "impressions", "clicks", "spend", "sales", "orders", "report_date"
        );

        return ImportPreviewVo.builder()
                .headers(headers)
                .rows(rows)
                .totalRows(job.getTotalRows())
                .build();
    }

    @Override
    @Transactional
    public ImportJobVo mapImport(String id, ImportMappingRequest request) {
        ImportJobEntity job = importJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入任务不存在: " + id);
        }
        if (request.getMapping() == null || request.getMapping().isEmpty()) {
            throw new BusinessException("EMPTY_MAPPING", "字段映射不能为空");
        }

        String mappingJson;
        try {
            mappingJson = objectMapper.writeValueAsString(request.getMapping());
        } catch (JsonProcessingException e) {
            throw new BusinessException("MAPPING_SERIALIZE_ERROR", "映射配置序列化失败");
        }

        job.setMappingConfig(mappingJson);
        job.setStatus("mapped");
        importJobMapper.updateById(job);

        log.info("Import mapping saved: id={}", id);
        return toVo(job);
    }

    @Override
    @Transactional
    public ImportValidationVo validateImport(String id) {
        ImportJobEntity job = importJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入任务不存在: " + id);
        }
        assertJobReadable(job);

        // Clear previous errors
        LambdaQueryWrapper<ImportRowErrorEntity> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(ImportRowErrorEntity::getImportJobId, job.getId());
        importRowErrorMapper.delete(deleteWrapper);

        LambdaQueryWrapper<RawSearchTermReportEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RawSearchTermReportEntity::getImportJobId, job.getId());
        List<RawSearchTermReportEntity> allRows = rawSearchTermReportMapper.selectList(wrapper);

        int totalRows = allRows.size();
        int validRows = 0;
        int invalidRows = 0;
        int duplicateRows = 0;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Track duplicates
        Set<String> seenKeys = new HashSet<>();

        for (int i = 0; i < allRows.size(); i++) {
            RawSearchTermReportEntity row = allRows.get(i);
            List<String> rowErrors = new ArrayList<>();
            boolean isValid = true;

            // Check required fields
            if (isBlank(row.getCampaignName())) {
                rowErrors.add("campaign_name为空");
                isValid = false;
            }
            if (isBlank(row.getCustomerSearchTerm())) {
                rowErrors.add("customer_search_term为空");
                isValid = false;
            }

            // Validate numeric fields
            if (row.getImpressions() != null && row.getImpressions() < 0) {
                rowErrors.add("impressions不能为负数");
                isValid = false;
            }
            if (row.getClicks() != null && row.getClicks() < 0) {
                rowErrors.add("clicks不能为负数");
                isValid = false;
            }
            if (row.getSpend() != null && row.getSpend().compareTo(BigDecimal.ZERO) < 0) {
                rowErrors.add("spend不能为负数");
                isValid = false;
            }
            if (row.getSales() != null && row.getSales().compareTo(BigDecimal.ZERO) < 0) {
                rowErrors.add("sales不能为负数");
                isValid = false;
            }

            // Logical validation: clicks <= impressions
            if (row.getClicks() != null && row.getImpressions() != null
                    && row.getClicks() > row.getImpressions()) {
                rowErrors.add("clicks不能大于impressions");
                isValid = false;
            }

            // Detect duplicates
            String dedupeKey = buildDedupeKey(row);
            if (seenKeys.contains(dedupeKey)) {
                duplicateRows++;
                warnings.add("第" + (i + 1) + "行与之前的记录重复: " + dedupeKey);
            } else {
                seenKeys.add(dedupeKey);
            }

            if (!isValid) {
                invalidRows++;
                String errorMsg = String.join("; ", rowErrors);
                errors.add("第" + (i + 1) + "行: " + errorMsg);
                saveRowError(job.getId(), i + 1, row.getRawData(), "VALIDATION_ERROR", errorMsg);
            } else {
                validRows++;
            }
        }

        // Update job with validation results
        job.setValidRows(validRows);
        job.setInvalidRows(invalidRows);
        job.setDuplicateRows(duplicateRows);
        job.setStatus(invalidRows == 0 ? "validated" : "validation_failed");
        importJobMapper.updateById(job);

        log.info("Import validation completed: id={}, valid={}, invalid={}, duplicates={}",
                id, validRows, invalidRows, duplicateRows);

        return ImportValidationVo.builder()
                .totalRows(totalRows)
                .validRows(validRows)
                .invalidRows(invalidRows)
                .duplicateRows(duplicateRows)
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    @Override
    @Transactional
    public ImportJobVo commitImport(String id, String userId) {
        ImportJobEntity job = importJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入任务不存在: " + id);
        }
        assertJobWritable(job);
        if (!"validated".equals(job.getStatus()) && !"validation_failed".equals(job.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "请先验证数据后再提交，当前状态: " + job.getStatus());
        }

        // M3: concurrent double-commit guard. @Transactional does NOT serialize two
        // concurrent commits — without this, both callers read a committable status
        // above and each proceed to double-insert. We atomically claim the job by
        // flipping its status to "committing" ONLY when it is still in a committable
        // state (validated / validation_failed). The conditional UPDATE takes a row
        // lock, so a second concurrent commit blocks and then matches 0 rows once the
        // winner has moved the status on — it is rejected here instead of re-inserting.
        // Because this UPDATE runs inside the same @Transactional as the insert work
        // below, a rolled-back / failed commit also rolls back this "committing"
        // transition: the job is RESTORED to its prior status and never stranded.
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ImportJobEntity> claimWrapper =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        claimWrapper.set("status", "committing")
                .eq("id", job.getId())
                .in("status", "validated", "validation_failed");
        int claimed = importJobMapper.update(null, claimWrapper);
        if (claimed != 1) {
            throw new BusinessException("INVALID_STATUS",
                    "导入任务当前不可提交（可能已提交或正在提交），请刷新后重试");
        }
        job.setStatus("committing");

        // Fetch validated raw rows only
        LambdaQueryWrapper<RawSearchTermReportEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RawSearchTermReportEntity::getImportJobId, job.getId());
        List<RawSearchTermReportEntity> allRows = rawSearchTermReportMapper.selectList(wrapper);

        // Collect row IDs that have validation errors - skip those
        LambdaQueryWrapper<ImportRowErrorEntity> errorWrapper = new LambdaQueryWrapper<>();
        errorWrapper.eq(ImportRowErrorEntity::getImportJobId, job.getId());
        List<ImportRowErrorEntity> rowErrors = importRowErrorMapper.selectList(errorWrapper);
        Set<Integer> errorRowNumbers = rowErrors.stream()
                .map(ImportRowErrorEntity::getRowNumber)
                .collect(Collectors.toSet());

        int committed = 0;
        for (int i = 0; i < allRows.size(); i++) {
            // Skip rows that had validation errors (row numbers are 1-based)
            if (errorRowNumbers.contains(i + 1)) {
                continue;
            }
            RawSearchTermReportEntity raw = allRows.get(i);

            // Look up campaign by name and store
            CampaignEntity campaign = findCampaignByName(raw.getStoreId(), raw.getCampaignName());
            if (campaign == null) {
                log.warn("Campaign not found for store={}, name={}, skipping row", raw.getStoreId(), raw.getCampaignName());
                saveRowError(job.getId(), i + 1, raw.getRawData(), "CAMPAIGN_NOT_FOUND",
                        "未找到对应的广告活动: " + raw.getCampaignName());
                continue;
            }

            // Look up ad group
            AdGroupEntity adGroup = findAdGroupByName(campaign.getId(), raw.getAdGroupName());

            // Upsert search term record
            upsertSearchTerm(raw, campaign, adGroup);

            // Upsert performance daily record (idempotent per store/entity/date)
            upsertPerformanceDaily(raw, campaign, adGroup);

            committed++;
        }

        job.setStatus("committed");
        importJobMapper.updateById(job);

        // Audit log
        auditLogService.createLog(
                userId != null ? UUID.fromString(userId) : null,
                null, "COMMIT", "import_job", job.getId(),
                Map.of("committedRows", committed, "totalRows", job.getTotalRows())
        );

        log.info("Import committed: id={}, committedRows={}", id, committed);
        return toVo(job);
    }

    @Override
    public List<ImportRowErrorVo> getImportErrors(String id) {
        ImportJobEntity job = importJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入任务不存在: " + id);
        }
        assertJobReadable(job);

        LambdaQueryWrapper<ImportRowErrorEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImportRowErrorEntity::getImportJobId, job.getId())
                .orderByAsc(ImportRowErrorEntity::getRowNumber);

        return importRowErrorMapper.selectList(wrapper).stream()
                .map(this::toErrorVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ImportJobVo reanalyzeImport(String id, String userId) {
        ImportJobEntity job = importJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入任务不存在: " + id);
        }
        assertJobWritable(job);

        // Clear previous row errors
        LambdaQueryWrapper<ImportRowErrorEntity> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(ImportRowErrorEntity::getImportJobId, job.getId());
        importRowErrorMapper.delete(deleteWrapper);

        // Re-count from raw table
        LambdaQueryWrapper<RawSearchTermReportEntity> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(RawSearchTermReportEntity::getImportJobId, job.getId());
        Long totalRows = rawSearchTermReportMapper.selectCount(countWrapper);

        job.setTotalRows(totalRows != null ? totalRows.intValue() : 0);
        job.setValidRows(0);
        job.setInvalidRows(0);
        job.setDuplicateRows(0);
        job.setStatus("uploaded");
        job.setErrorMessage(null);
        importJobMapper.updateById(job);

        // Re-run validation
        ImportValidationVo validation = validateImport(id);

        // Audit log
        auditLogService.createLog(
                userId != null ? UUID.fromString(userId) : null,
                null, "REANALYZE", "import_job", job.getId(),
                Map.of("totalRows", validation.getTotalRows(), "validRows", validation.getValidRows())
        );

        log.info("Import reanalyzed: id={}", id);
        return getImport(id);
    }

    // ---- Private helpers ----

    /**
     * Upserts the aggregated {@code search_terms} row for a committed report line.
     *
     * <p><b>Grain / additive semantics.</b> Search-term metrics are aggregated
     * ADDITIVELY across every imported day into a single row per
     * {@code (campaign_id, ad_group_id, search_term)} — unlike
     * {@link #upsertPerformanceDaily} which overwrites per day. That tuple is the
     * true grain and is enforced in the database by the {@code uq_search_term_grain}
     * unique index (see {@code db/schema.sql}).</p>
     *
     * <p><b>M4 — NPE hardening.</b> An existing row may carry {@code null} counters
     * or amounts (legacy data, partial writes). Every existing metric getter is
     * null-coalesced before arithmetic, mirroring the {@code != null ? x : ZERO/0}
     * pattern used in the insert branch, so aggregation never NPEs.</p>
     *
     * <p><b>M4 — insert/update race.</b> Two concurrent imports can both observe a
     * {@code null} from {@code selectOne} and each try to insert the same grain.
     * The unique index makes the losing insert fail with a duplicate-key error; we
     * catch it and fall back to re-select + additive update so no work is lost and
     * no duplicate row is created.</p>
     */
    private void upsertSearchTerm(RawSearchTermReportEntity raw, CampaignEntity campaign, AdGroupEntity adGroup) {
        SearchTermEntity existing = findExistingSearchTerm(raw, campaign, adGroup);
        if (existing != null) {
            aggregateSearchTerm(existing, raw);
            return;
        }

        BigDecimal spend = raw.getSpend() != null ? raw.getSpend() : BigDecimal.ZERO;
        BigDecimal sales = raw.getSales() != null ? raw.getSales() : BigDecimal.ZERO;
        int clicks = raw.getClicks() != null ? raw.getClicks() : 0;
        long impressions = raw.getImpressions() != null ? raw.getImpressions() : 0;
        int orders = raw.getOrders() != null ? raw.getOrders() : 0;

        BigDecimal ctr = impressions > 0 ? BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal cvr = clicks > 0 ? BigDecimal.valueOf(orders).divide(BigDecimal.valueOf(clicks), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgCpc = clicks > 0 ? spend.divide(BigDecimal.valueOf(clicks), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal acos = sales.compareTo(BigDecimal.ZERO) > 0 ? spend.divide(sales, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal roas = spend.compareTo(BigDecimal.ZERO) > 0 ? sales.divide(spend, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        SearchTermEntity newTerm = SearchTermEntity.builder()
                .campaignId(campaign.getId())
                .adGroupId(adGroup != null ? adGroup.getId() : null)
                .storeId(raw.getStoreId())
                .searchTerm(raw.getCustomerSearchTerm())
                .impressions(impressions)
                .clicks(clicks)
                .spend(spend)
                .sales(sales)
                .orders(orders)
                .acos(acos)
                .ctr(ctr)
                .cvr(cvr)
                .avgCpc(avgCpc)
                .roas(roas)
                .harvested(false)
                .build();
        try {
            searchTermMapper.insert(newTerm);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Race: a concurrent import inserted the same grain between our selectOne
            // and this insert. The uq_search_term_grain index rejected the duplicate;
            // re-select the now-present row and apply our metrics additively.
            log.debug("Concurrent search-term insert for grain campaign={}, adGroup={}, term={}; falling back to update",
                    campaign.getId(), adGroup != null ? adGroup.getId() : null, raw.getCustomerSearchTerm());
            SearchTermEntity concurrent = findExistingSearchTerm(raw, campaign, adGroup);
            if (concurrent == null) {
                throw e;
            }
            aggregateSearchTerm(concurrent, raw);
        }
    }

    private SearchTermEntity findExistingSearchTerm(RawSearchTermReportEntity raw, CampaignEntity campaign, AdGroupEntity adGroup) {
        // Search for existing search term in the same campaign / ad group grain.
        LambdaQueryWrapper<SearchTermEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SearchTermEntity::getCampaignId, campaign.getId())
                .eq(SearchTermEntity::getSearchTerm, raw.getCustomerSearchTerm());
        if (adGroup != null) {
            wrapper.eq(SearchTermEntity::getAdGroupId, adGroup.getId());
        }
        return searchTermMapper.selectOne(wrapper);
    }

    /**
     * Additively merges the raw row's metrics into an existing search-term row and
     * persists it. M4: every existing getter is null-coalesced before arithmetic so
     * a row with {@code null} counters/amounts does not throw an NPE.
     */
    private void aggregateSearchTerm(SearchTermEntity existing, RawSearchTermReportEntity raw) {
        long existingImpressions = existing.getImpressions() != null ? existing.getImpressions() : 0L;
        int existingClicks = existing.getClicks() != null ? existing.getClicks() : 0;
        BigDecimal existingSpend = existing.getSpend() != null ? existing.getSpend() : BigDecimal.ZERO;
        BigDecimal existingSales = existing.getSales() != null ? existing.getSales() : BigDecimal.ZERO;
        int existingOrders = existing.getOrders() != null ? existing.getOrders() : 0;

        long impressions = existingImpressions + (raw.getImpressions() != null ? raw.getImpressions() : 0);
        int clicks = existingClicks + (raw.getClicks() != null ? raw.getClicks() : 0);
        BigDecimal spend = existingSpend.add(raw.getSpend() != null ? raw.getSpend() : BigDecimal.ZERO);
        BigDecimal sales = existingSales.add(raw.getSales() != null ? raw.getSales() : BigDecimal.ZERO);
        int orders = existingOrders + (raw.getOrders() != null ? raw.getOrders() : 0);

        existing.setImpressions(impressions);
        existing.setClicks(clicks);
        existing.setSpend(spend);
        existing.setSales(sales);
        existing.setOrders(orders);

        // Recalculate derived metrics from the coalesced totals.
        if (impressions > 0 && clicks > 0) {
            existing.setCtr(BigDecimal.valueOf(clicks)
                    .divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP));
        }
        if (clicks > 0 && orders > 0) {
            existing.setCvr(BigDecimal.valueOf(orders)
                    .divide(BigDecimal.valueOf(clicks), 6, RoundingMode.HALF_UP));
            existing.setAvgCpc(spend
                    .divide(BigDecimal.valueOf(clicks), 4, RoundingMode.HALF_UP));
        }
        if (sales.compareTo(BigDecimal.ZERO) > 0) {
            existing.setAcos(spend.divide(sales, 6, RoundingMode.HALF_UP));
            existing.setRoas(sales
                    .divide(spend.compareTo(BigDecimal.ZERO) > 0 ? spend : BigDecimal.ONE, 4, RoundingMode.HALF_UP));
        }
        existing.setUpdatedAt(LocalDateTime.now());
        searchTermMapper.updateById(existing);
    }

    /**
     * Upserts the {@code performance_daily} row for a committed report line,
     * keyed by the same columns as the {@code uq_perf_daily} unique constraint:
     * {@code (store_id, entity_type, entity_id, report_date)}.
     *
     * <p><b>Overwrite semantics.</b> A daily search-term report row is a full
     * restatement of that entity's metrics for that day, not an incremental
     * delta, so re-importing the same (store, entity, date) must replace the
     * existing metric values rather than add to them. Previously this method
     * always inserted a fresh row, so re-importing a day double-counted its
     * metrics (and would collide with the unique constraint in the database).
     * We therefore look up the existing row by the constraint tuple and, when
     * present, overwrite its metrics; otherwise we insert a new row.</p>
     */
    private void upsertPerformanceDaily(RawSearchTermReportEntity raw, CampaignEntity campaign, AdGroupEntity adGroup) {
        // When the report line carries no explicit date, default to the store's
        // marketplace civil day (not the server JVM zone), since performance rows
        // are stored/partitioned per marketplace-local date.
        LocalDate reportDate = raw.getReportDate() != null
                ? raw.getReportDate()
                : LocalDate.now(zoneForStore(raw.getStoreId()));
        BigDecimal spend = raw.getSpend() != null ? raw.getSpend() : BigDecimal.ZERO;
        BigDecimal sales = raw.getSales() != null ? raw.getSales() : BigDecimal.ZERO;
        int clicks = raw.getClicks() != null ? raw.getClicks() : 0;
        long impressions = raw.getImpressions() != null ? raw.getImpressions() : 0;
        int orders = raw.getOrders() != null ? raw.getOrders() : 0;

        BigDecimal ctr = impressions > 0 ? BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal cvr = clicks > 0 ? BigDecimal.valueOf(orders).divide(BigDecimal.valueOf(clicks), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgCpc = clicks > 0 ? spend.divide(BigDecimal.valueOf(clicks), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal acos = sales.compareTo(BigDecimal.ZERO) > 0 ? spend.divide(sales, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal roas = spend.compareTo(BigDecimal.ZERO) > 0 ? sales.divide(spend, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // Look up any existing row for the unique key (store, entity_type, entity_id, report_date).
        LambdaQueryWrapper<PerformanceDailyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceDailyEntity::getStoreId, raw.getStoreId())
                .eq(PerformanceDailyEntity::getEntityType, "campaign")
                .eq(PerformanceDailyEntity::getEntityId, campaign.getId())
                .eq(PerformanceDailyEntity::getDate, reportDate)
                .last("LIMIT 1");
        PerformanceDailyEntity existing = performanceDailyMapper.selectOne(wrapper);

        if (existing != null) {
            // Full restatement: overwrite the day's metrics, do not accumulate.
            existing.setCampaignId(campaign.getId());
            existing.setImpressions(impressions);
            existing.setClicks(clicks);
            existing.setSpend(spend);
            existing.setSales(sales);
            existing.setOrders(orders);
            existing.setAcos(acos);
            existing.setRoas(roas);
            existing.setCtr(ctr);
            existing.setCvr(cvr);
            existing.setAvgCpc(avgCpc);
            existing.setUpdatedAt(LocalDateTime.now());
            performanceDailyMapper.updateById(existing);
            return;
        }

        PerformanceDailyEntity perf = PerformanceDailyEntity.builder()
                .storeId(raw.getStoreId())
                .campaignId(campaign.getId())
                .entityType("campaign")
                .entityId(campaign.getId())
                .date(reportDate)
                .impressions(impressions)
                .clicks(clicks)
                .spend(spend)
                .sales(sales)
                .orders(orders)
                .acos(acos)
                .roas(roas)
                .ctr(ctr)
                .cvr(cvr)
                .avgCpc(avgCpc)
                .build();
        performanceDailyMapper.insert(perf);
    }

    /**
     * Resolve the civil-day timezone for a store via its marketplace. Falls back to
     * UTC (never the JVM default) when the store is unknown or has no marketplace.
     */
    private ZoneId zoneForStore(UUID storeId) {
        UUID marketplaceId = null;
        if (storeId != null) {
            StoreEntity store = storeMapper.selectById(storeId);
            if (store != null) {
                marketplaceId = store.getMarketplaceId();
            }
        }
        return marketplaceReferenceService.timezoneForMarketplace(marketplaceId);
    }

    private CampaignEntity findCampaignByName(UUID storeId, String campaignName) {
        if (campaignName == null || campaignName.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<CampaignEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CampaignEntity::getStoreId, storeId)
                .eq(CampaignEntity::getName, campaignName)
                .last("LIMIT 1");
        return campaignMapper.selectOne(wrapper);
    }

    private AdGroupEntity findAdGroupByName(UUID campaignId, String adGroupName) {
        if (adGroupName == null || adGroupName.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<AdGroupEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdGroupEntity::getCampaignId, campaignId)
                .eq(AdGroupEntity::getName, adGroupName)
                .last("LIMIT 1");
        return adGroupMapper.selectOne(wrapper);
    }

    private void saveRowError(UUID importJobId, int rowNumber, String rawData, String errorCode, String errorMessage) {
        ImportRowErrorEntity error = ImportRowErrorEntity.builder()
                .importJobId(importJobId)
                .rowNumber(rowNumber)
                .rawData(rawData)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
        importRowErrorMapper.insert(error);
    }

    private String buildDedupeKey(RawSearchTermReportEntity row) {
        return String.join("|",
                nvl(row.getCampaignName()),
                nvl(row.getAdGroupName()),
                nvl(row.getCustomerSearchTerm()),
                nvl(row.getMatchType()),
                row.getReportDate() != null ? row.getReportDate().toString() : "");
    }

    private String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result.toArray(new String[0]);
    }

    private String getValue(Map<String, String> row, String... keys) {
        for (String key : keys) {
            String val = row.get(key);
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return null;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.replace(",", "").replace(".0", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", "").replace("$", "").trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            try {
                // Try common US format: MM/DD/YYYY
                String[] parts = value.trim().split("/");
                if (parts.length == 3) {
                    return LocalDate.of(
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1])
                    );
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private ImportJobVo toVo(ImportJobEntity entity) {
        return ImportJobVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .marketplaceId(entity.getMarketplaceId() != null ? entity.getMarketplaceId().toString() : null)
                .reportType(entity.getReportType())
                .fileName(entity.getFileName())
                .fileSize(entity.getFileSize() != null ? entity.getFileSize() : 0)
                .status(entity.getStatus())
                .totalRows(entity.getTotalRows() != null ? entity.getTotalRows() : 0)
                .validRows(entity.getValidRows() != null ? entity.getValidRows() : 0)
                .invalidRows(entity.getInvalidRows() != null ? entity.getInvalidRows() : 0)
                .duplicateRows(entity.getDuplicateRows() != null ? entity.getDuplicateRows() : 0)
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private ImportRowErrorVo toErrorVo(ImportRowErrorEntity entity) {
        return ImportRowErrorVo.builder()
                .id(entity.getId().toString())
                .importJobId(entity.getImportJobId().toString())
                .rowNumber(entity.getRowNumber())
                .rawData(entity.getRawData())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private String nvl(String value) {
        return value != null ? value : "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
