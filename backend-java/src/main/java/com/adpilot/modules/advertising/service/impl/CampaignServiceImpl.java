package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.converter.CampaignConverter;
import com.adpilot.modules.advertising.dto.CampaignBulkRequest;
import com.adpilot.modules.advertising.dto.CampaignCreateRequest;
import com.adpilot.modules.advertising.dto.CampaignHostingRequest;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.AdMetrics;
import com.adpilot.modules.advertising.support.CampaignFilter;
import com.adpilot.modules.advertising.support.CampaignTableColumns;
import com.adpilot.modules.advertising.vo.CampaignBulkResultVo;
import com.adpilot.modules.advertising.vo.CampaignTrendPointVo;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.tableview.dto.ExportRequest;
import com.adpilot.modules.tableview.filter.FilterFieldSpec;
import com.adpilot.modules.tableview.filter.FilterTranslator;
import com.adpilot.modules.tableview.support.CsvWriter;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private final CampaignMapper campaignMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final OperationMapper operationMapper;
    private final ObjectMapper objectMapper;
    private final FilterTranslator filterTranslator;

    /** Store + owner scope target for campaigns (Req 7.1.5). */
    private static final ScopeTarget CAMPAIGN_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");
    private final DataScopeService dataScopeService;

    /** Default Hosting_Goal applied when a hosting request omits one (Req 21.1). */
    private static final String DEFAULT_HOSTING_GOAL = "maximize_sales_at_target";

    /** Operation {@code entity_type} value for a Campaign (Req 12.7 synced-list gating). */
    private static final String CAMPAIGN_ENTITY_TYPE = "campaign";

    /** Machine value for the creation Operation_Source (Req 12.7 synced-list gating). */
    private static final String CREATION_SOURCE = OperationMachineValues.toValue(OperationSource.CREATION);

    /** Machine value for the {@code effective} Sync_State (Req 12.7 synced-list gating). */
    private static final String EFFECTIVE_SYNC_STATE = OperationMachineValues.toValue(SyncState.EFFECTIVE);

    /** Page size for the streaming CSV export's paged reads (Req 2.8). */
    private static final int EXPORT_PAGE_SIZE = 500;

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<CampaignVo> listCampaigns(CampaignFilter filter, String goalId, int page, int pageSize) {
        QueryWrapper<CampaignEntity> wrapper = buildWrapper(filter, goalId);
        wrapper.orderByDesc("created_at");

        // parentAsin / targetingGoal have no dedicated column (they are resolved
        // from related data and held as non-persisted attributes), so they are
        // applied via the pure predicate in memory. The remaining filters are
        // pushed down to SQL above. When no in-memory-only filter is active we
        // can paginate directly in the database; otherwise we filter the scoped
        // result set in memory and paginate manually to keep totals correct.
        boolean inMemoryRefinement = filter != null
                && (filter.getParentAsin() != null || filter.getTargetingGoal() != null);

        if (!inMemoryRefinement) {
            Page<CampaignEntity> result = campaignMapper.selectPage(new Page<>(page, pageSize), wrapper);
            List<CampaignEntity> records = result.getRecords();
            Set<UUID> effectiveIds = creationEffectiveCampaignIds(records);
            List<CampaignVo> voList = records.stream()
                    .map(e -> CampaignConverter.toVo(e, effectiveIds.contains(e.getId())))
                    .collect(Collectors.toList());
            return PageResponse.of(voList, result.getTotal(), page, pageSize);
        }

        List<CampaignEntity> scoped = campaignMapper.selectList(wrapper);
        CampaignFilter refinementFilter = Objects.requireNonNull(filter);
        List<CampaignEntity> matched = scoped.stream()
                .filter(e -> refinementFilter.matches(CampaignConverter.toView(e)))
                .collect(Collectors.toList());

        long total = matched.size();
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(matched.size(), from + pageSize);
        List<CampaignEntity> pageRecords = (from >= matched.size())
                ? List.of()
                : matched.subList(from, to);
        Set<UUID> effectiveIds = creationEffectiveCampaignIds(pageRecords);
        List<CampaignVo> voList = pageRecords.stream()
                .map(e -> CampaignConverter.toVo(e, effectiveIds.contains(e.getId())))
                .collect(Collectors.toList());
        return PageResponse.of(voList, total, page, pageSize);
    }

    /**
     * Resolve, for a page of campaigns, which campaigns' creation Operation has reached the
     * {@code effective} Sync_State (Req 12.7, 12.8). A Campaign is included in the Amazon-synced
     * list only when it both has a non-empty {@code amazon_campaign_id} and appears in this set
     * (an {@code amazon_import} Campaign is treated as already effective by the converter). The
     * lookup is a single batched query over the {@code operations} table keyed by the page's
     * campaign ids, so the gate adds at most one query per page.
     *
     * @param campaigns the page of campaign entities (may be empty)
     * @return the subset of campaign ids whose creation Operation is {@code effective}
     */
    private Set<UUID> creationEffectiveCampaignIds(Collection<CampaignEntity> campaigns) {
        if (campaigns == null || campaigns.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = campaigns.stream()
                .map(CampaignEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Set.of();
        }
        QueryWrapper<OperationEntity> wrapper = new QueryWrapper<>();
        wrapper.select("entity_id")
                .eq("entity_type", CAMPAIGN_ENTITY_TYPE)
                .eq("operation_source", CREATION_SOURCE)
                .eq("sync_state", EFFECTIVE_SYNC_STATE)
                .in("entity_id", ids);
        List<OperationEntity> rows = operationMapper.selectList(wrapper);
        Set<UUID> effective = new HashSet<>();
        for (OperationEntity row : rows) {
            if (row.getEntityId() != null) {
                effective.add(row.getEntityId());
            }
        }
        return effective;
    }

    /** True iff the given campaign's creation Operation reached the {@code effective} Sync_State. */
    private boolean creationOperationEffective(UUID campaignId) {
        if (campaignId == null) {
            return false;
        }
        QueryWrapper<OperationEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("entity_type", CAMPAIGN_ENTITY_TYPE)
                .eq("operation_source", CREATION_SOURCE)
                .eq("sync_state", EFFECTIVE_SYNC_STATE)
                .eq("entity_id", campaignId);
        return operationMapper.selectCount(wrapper) > 0;
    }

    /**
     * Stream the full filtered, sorted campaign result set as CSV (Req 2.8).
     *
     * <p>The visible columns are validated up front (each must be a known
     * campaign column and at least one is required). The active filters are
     * validated and translated by the shared {@link FilterTranslator} (the same
     * component the query endpoint, task 16.1, uses) and the active sort is
     * applied before paging. The caller's data scope is then applied so the
     * export only contains rows the requester may read.
     *
     * <p>Rows are read in fixed-size pages and written incrementally so the full
     * matching set is never materialized in memory. A stable {@code id} tiebreaker
     * is appended to the sort so paging is deterministic even when the requested
     * sort columns contain ties.
     */
    @Override
    public void exportCsv(ExportRequest request, Writer writer) {
        if (request == null) {
            throw new BusinessException("EXPORT_REQUEST_REQUIRED", "Export request is required");
        }
        List<String> visibleColumns = request.getVisibleColumns();
        if (visibleColumns == null || visibleColumns.isEmpty()) {
            throw new BusinessException("EXPORT_NO_COLUMNS",
                    "At least one visible column is required for export");
        }
        // Validate every requested column before doing any work (fail fast).
        for (String key : visibleColumns) {
            CampaignTableColumns.require(key);
        }

        Map<String, FilterFieldSpec> registry = CampaignTableColumns.filterRegistry();
        QueryWrapper<CampaignEntity> wrapper = new QueryWrapper<>();

        // Apply the requester's data scope first, then AND the validated filter
        // conditions on top so the scope predicate can never be weakened (the
        // shared translator nests the conditions in a single AND group).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, CAMPAIGN_SCOPE, user);
        }
        filterTranslator.apply(wrapper, request.getFilters(), registry);

        // Active sort (validated against the same field registry), else the
        // list endpoint's default (newest first).
        if (request.getSortField() != null && !request.getSortField().isBlank()) {
            if (!CampaignTableColumns.has(request.getSortField())) {
                throw new BusinessException("EXPORT_INVALID_SORT",
                        "Unknown sort field: " + request.getSortField());
            }
            wrapper.orderBy(true, !request.descending(),
                    CampaignTableColumns.sqlColumn(request.getSortField()));
        } else {
            wrapper.orderByDesc("created_at");
        }
        // Deterministic tiebreaker so paged reads never skip or repeat rows.
        wrapper.orderByAsc("id");

        CsvWriter csv = new CsvWriter(writer);
        // Header row: visible columns in their configured order.
        List<String> header = visibleColumns.stream()
                .map(CampaignTableColumns::header)
                .collect(Collectors.toList());
        csv.writeRow(header);

        int page = 1;
        while (true) {
            Page<CampaignEntity> pageReq = new Page<>(page, EXPORT_PAGE_SIZE);
            pageReq.setSearchCount(false); // streaming: no per-page COUNT(*) needed
            Page<CampaignEntity> result = campaignMapper.selectPage(pageReq, wrapper);
            List<CampaignEntity> records = result.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            for (CampaignEntity entity : records) {
                CampaignVo vo = CampaignConverter.toVo(entity);
                List<String> row = visibleColumns.stream()
                        .map(key -> CampaignTableColumns.cell(vo, key))
                        .collect(Collectors.toList());
                csv.writeRow(row);
            }
            csv.flush();
            if (records.size() < EXPORT_PAGE_SIZE) {
                break;
            }
            page++;
        }
        csv.flush();
    }

    /**
     * Translate the column-backed portion of a {@link CampaignFilter} into a
     * MyBatis-Plus {@link QueryWrapper} and apply the caller's data scope. The
     * {@code goalId} filter is retained from the original endpoint contract.
     */
    private QueryWrapper<CampaignEntity> buildWrapper(CampaignFilter filter, String goalId) {
        QueryWrapper<CampaignEntity> wrapper = new QueryWrapper<>();

        if (filter != null) {
            if (filter.getStoreId() != null) {
                wrapper.eq("store_id", filter.getStoreId());
            }
            if (filter.getStatus() != null) {
                wrapper.eq("status", filter.getStatus());
            }
            if (filter.getAdType() != null) {
                wrapper.eq("campaign_type", filter.getAdType());
            }
            if (filter.getPortfolioId() != null) {
                wrapper.eq("portfolio_id", filter.getPortfolioId());
            }
            if (filter.getTargetAcosMin() != null) {
                wrapper.isNotNull("target_acos").ge("target_acos", filter.getTargetAcosMin());
            }
            if (filter.getTargetAcosMax() != null) {
                wrapper.isNotNull("target_acos").le("target_acos", filter.getTargetAcosMax());
            }
            applySmartFilter(wrapper, filter.getSmartFilter());
        }
        if (goalId != null && !goalId.isBlank()) {
            wrapper.eq("goal_id", goalId);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, CAMPAIGN_SCOPE, user);
        }
        return wrapper;
    }

    /** Push a smart-filter preset down to SQL, mirroring {@link CampaignFilter.SmartFilter}. */
    private void applySmartFilter(QueryWrapper<CampaignEntity> wrapper, CampaignFilter.SmartFilter preset) {
        if (preset == null) {
            return;
        }
        switch (preset) {
            case AI_MANAGED -> wrapper.eq("ai_managed", 1);
            case HOSTED -> wrapper.eq("hosting_enabled", 1);
            case UNHOSTED -> wrapper.eq("hosting_enabled", 0);
            case OVER_TARGET -> wrapper.isNotNull("target_acos").apply("acos > target_acos");
            case UNDER_TARGET -> wrapper.isNotNull("target_acos").apply("acos <= target_acos");
        }
    }

    @Override
    public CampaignVo getCampaignById(String id) {
        CampaignEntity entity = campaignMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return CampaignConverter.toVo(entity, creationOperationEffective(entity.getId()));
    }

    @Override
    @Transactional
    public CampaignVo updateCampaign(String id, String name, String status, BigDecimal budget, String userId) {
        CampaignEntity entity = campaignMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + id);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        if (name != null) entity.setName(name);
        if (status != null) entity.setStatus(status);
        if (budget != null) entity.setBudget(budget);

        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        campaignMapper.updateById(entity);
        log.info("Campaign updated: id={}", id);

        return CampaignConverter.toVo(entity);
    }

    @Override
    @Transactional
    public CampaignVo createCampaign(CampaignCreateRequest request, String userId) {
        CampaignEntity entity = CampaignEntity.builder()
                .storeId(parseUuid(request.getStoreId(), "storeId"))
                .goalId(request.getGoalId() != null && !request.getGoalId().isBlank()
                        ? parseUuid(request.getGoalId(), "goalId") : null)
                .name(request.getName())
                .campaignType(request.getCampaignType())
                .portfolio(request.getPortfolio())
                .portfolioId(request.getPortfolioId() != null && !request.getPortfolioId().isBlank()
                        ? parseUuid(request.getPortfolioId(), "portfolioId") : null)
                .status(request.getStatus() != null && !request.getStatus().isBlank()
                        ? normalizeState(request.getStatus()) : "enabled")
                .budget(request.getBudget())
                .budgetType(request.getBudgetType() != null && !request.getBudgetType().isBlank()
                        ? request.getBudgetType() : "daily")
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .targetingType(request.getTargetingType())
                .tags(toJson(request.getTags()))
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .updatedBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        campaignMapper.insert(entity);
        log.info("Campaign created: id={}, name={}", entity.getId(), entity.getName());
        return CampaignConverter.toVo(entity);
    }

    @Override
    @Transactional
    public CampaignVo updateState(String id, String state, String userId) {
        String normalized = normalizeState(state);
        CampaignEntity entity = campaignMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + id);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        entity.setStatus(normalized);
        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        campaignMapper.updateById(entity);
        log.info("Campaign state changed: id={}, state={}", id, normalized);

        return CampaignConverter.toVo(entity);
    }

    @Override
    public List<CampaignBulkResultVo> bulkOperation(CampaignBulkRequest request, String userId) {
        String operation = request.getOperation() == null ? "" : request.getOperation().trim().toLowerCase();
        if (!operation.equals("enable") && !operation.equals("pause") && !operation.equals("delete")) {
            throw new BusinessException("INVALID_BULK_OPERATION",
                    "Unsupported bulk operation: " + request.getOperation());
        }

        List<CampaignBulkResultVo> results = new ArrayList<>();
        for (String id : request.getIds()) {
            try {
                switch (operation) {
                    case "enable" -> updateState(id, "enabled", userId);
                    case "pause" -> updateState(id, "paused", userId);
                    case "delete" -> deleteCampaign(id, userId);
                    default -> throw new BusinessException("INVALID_BULK_OPERATION",
                            "Unsupported bulk operation: " + operation);
                }
                results.add(CampaignBulkResultVo.builder()
                        .id(id).success(true).message(operation + " applied").build());
            } catch (BusinessException ex) {
                results.add(CampaignBulkResultVo.builder()
                        .id(id).success(false).message(ex.getMessage()).build());
            } catch (Exception ex) {
                log.warn("Bulk {} failed for campaign {}", operation, id, ex);
                results.add(CampaignBulkResultVo.builder()
                        .id(id).success(false).message("Operation failed").build());
            }
        }
        return results;
    }

    @Override
    public List<CampaignTrendPointVo> getTrend(String storeId, String startDate, String endDate, String granularity) {
        QueryWrapper<PerformanceDailyEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", parseUuid(storeId, "storeId"));
        }
        LocalDate from = parseDate(startDate, "startDate");
        LocalDate to = parseDate(endDate, "endDate");
        if (from != null) {
            wrapper.ge("date", from);
        }
        if (to != null) {
            wrapper.le("date", to);
        }
        wrapper.isNotNull("date");

        List<PerformanceDailyEntity> rows = performanceDailyMapper.selectList(wrapper);

        String gran = granularity == null ? "day" : granularity.trim().toLowerCase();
        // Accumulate per-period sums in chronological order (LinkedHashMap keyed
        // by the LocalDate bucket start so we can sort deterministically).
        Map<LocalDate, double[]> buckets = new LinkedHashMap<>();
        for (PerformanceDailyEntity row : rows) {
            if (row.getDate() == null) {
                continue;
            }
            LocalDate key = bucketKey(row.getDate(), gran);
            double[] acc = buckets.computeIfAbsent(key, k -> new double[5]);
            acc[0] += row.getSpend() != null ? row.getSpend().doubleValue() : 0;
            acc[1] += row.getSales() != null ? row.getSales().doubleValue() : 0;
            acc[2] += row.getOrders() != null ? row.getOrders() : 0;
            acc[3] += row.getClicks() != null ? row.getClicks() : 0;
            acc[4] += row.getImpressions() != null ? row.getImpressions() : 0;
        }

        return buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    double[] acc = e.getValue();
                    BigDecimal spend = BigDecimal.valueOf(acc[0]);
                    BigDecimal sales = BigDecimal.valueOf(acc[1]);
                    int orders = (int) Math.round(acc[2]);
                    int clicks = (int) Math.round(acc[3]);
                    long impressions = Math.round(acc[4]);
                    double acos = AdMetrics.acos(spend, sales).doubleValue();
                    double cpc = clicks > 0 ? acc[0] / clicks : 0;
                    double costPerOrder = orders > 0 ? acc[0] / orders : 0;
                    return CampaignTrendPointVo.builder()
                            .period(formatPeriod(e.getKey(), gran))
                            .spend(acc[0])
                            .sales(acc[1])
                            .orders(orders)
                            .clicks(clicks)
                            .impressions(impressions)
                            .acos(acos)
                            .cpc(cpc)
                            .costPerOrder(costPerOrder)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public CampaignVo assignHosting(String id, CampaignHostingRequest request, String userId) {
        // targetAcos presence/positivity is enforced by bean validation on the
        // request (Req 21.6); guard defensively in case the service is called
        // directly (e.g. from a test or another service).
        if (request == null || request.getTargetAcos() == null
                || request.getTargetAcos().signum() <= 0) {
            throw new BusinessException("TARGET_ACOS_REQUIRED",
                    "Target ACoS is required to place a campaign under AI hosting");
        }

        CampaignEntity entity = campaignMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        entity.setHostingEnabled(true);
        entity.setAiManaged(true);
        entity.setTargetAcos(request.getTargetAcos());
        entity.setHostingGoal(request.getHostingGoal() != null && !request.getHostingGoal().isBlank()
                ? request.getHostingGoal() : DEFAULT_HOSTING_GOAL);
        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        campaignMapper.updateById(entity);
        log.info("Campaign placed under AI hosting: id={}, goal={}, targetAcos={}",
                id, entity.getHostingGoal(), entity.getTargetAcos());

        return CampaignConverter.toVo(entity);
    }

    @Override
    @Transactional
    public CampaignVo removeHosting(String id, String userId) {
        CampaignEntity entity = campaignMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        // Persist the un-hosted state so the optimizer stops touching this
        // campaign (Req 21.5). The previously assigned goal / target are cleared
        // since they no longer apply.
        entity.setHostingEnabled(false);
        entity.setAiManaged(false);
        entity.setHostingGoal(null);
        entity.setTargetAcos(null);
        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        campaignMapper.updateById(entity);
        log.info("Campaign removed from AI hosting: id={}", id);

        return CampaignConverter.toVo(entity);
    }

    /** Delete a campaign, enforcing write scope; used by the bulk delete op. */
    @Transactional
    protected void deleteCampaign(String id, String userId) {
        CampaignEntity entity = campaignMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }
        campaignMapper.deleteById(entity.getId());
        log.info("Campaign deleted: id={}", id);
    }

    /**
     * Normalize an enable/pause token to the persisted status value. Accepts
     * {@code enable}/{@code enabled}/{@code active} → {@code enabled} and
     * {@code pause}/{@code paused} → {@code paused}; anything else is a domain
     * error (400).
     */
    private String normalizeState(String state) {
        String s = state == null ? "" : state.trim().toLowerCase();
        return switch (s) {
            case "enable", "enabled", "active" -> "enabled";
            case "pause", "paused" -> "paused";
            default -> throw new BusinessException("INVALID_CAMPAIGN_STATE",
                    "Unsupported campaign state: " + state);
        };
    }

    /** Bucket a date to the start key for the requested granularity. */
    private LocalDate bucketKey(LocalDate date, String granularity) {
        return switch (granularity) {
            case "week" -> date.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                    .with(java.time.DayOfWeek.MONDAY);
            case "month" -> date.withDayOfMonth(1);
            default -> date; // day
        };
    }

    /** Format a bucket key as a human-readable period label. */
    private String formatPeriod(LocalDate key, String granularity) {
        return switch (granularity) {
            case "week" -> key.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                    + String.format("%02d", key.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "month" -> key.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default -> key.format(DateTimeFormatter.ISO_LOCAL_DATE);
        };
    }

    private LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException("INVALID_DATE",
                    "Invalid " + field + " (expected yyyy-MM-dd): " + value);
        }
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_ID", "Invalid " + field + ": " + value);
        }
    }

    private String toJson(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
