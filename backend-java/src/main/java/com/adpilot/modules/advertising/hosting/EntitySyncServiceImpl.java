package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.apisync.connector.AmazonLwaClient;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Default implementation of {@link EntitySyncService} that pulls Amazon Ads
 * entity metadata via the Amazon Ads API and upserts local entities plus
 * {@code external_entity_mappings} with origin {@code amazon_import}.
 *
 * <p>Validates: Requirements 14.1, 14.4, 14.5.</p>
 */
@Slf4j
@Service
public class EntitySyncServiceImpl implements EntitySyncService {

    static final String PLATFORM = "amazon_ads";
    static final String ORIGIN_AMAZON_IMPORT = "amazon_import";

    /** Max results requested per Amazon Ads list page (v3 pagination). */
    static final int ENTITY_LIST_PAGE_SIZE = 500;
    /** Absolute cap on entities pulled per type, guarding against runaway paging. */
    static final int ENTITY_LIST_MAX_RESULTS = 10000;

    private final PlatformConnectionMapper connectionMapper;
    private final ExternalEntityMappingMapper mappingMapper;
    private final CampaignMapper campaignMapper;
    private final AdGroupMapper adGroupMapper;
    private final KeywordMapper keywordMapper;
    private final AmazonLwaClient lwaClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final RestClient http;

    public EntitySyncServiceImpl(PlatformConnectionMapper connectionMapper,
                                 ExternalEntityMappingMapper mappingMapper,
                                 CampaignMapper campaignMapper,
                                 AdGroupMapper adGroupMapper,
                                 KeywordMapper keywordMapper,
                                 AmazonLwaClient lwaClient,
                                 ObjectMapper objectMapper,
                                 TransactionTemplate transactionTemplate,
                                 HttpClientFactory httpClientFactory) {
        this.connectionMapper = connectionMapper;
        this.mappingMapper = mappingMapper;
        this.campaignMapper = campaignMapper;
        this.adGroupMapper = adGroupMapper;
        this.keywordMapper = keywordMapper;
        this.lwaClient = lwaClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.http = httpClientFactory.timeoutRestClientBuilder().build();
    }

    @Override
    public EntitySyncResult syncEntities(UUID storeId) {
        PlatformConnectionEntity connection = findAmazonAdsConnection(storeId);
        if (connection == null) {
            log.debug("No active Amazon Ads connection for store {}", storeId);
            return EntitySyncResult.failure(storeId, "No active Amazon Ads connection");
        }

        ConnectionContext ctx = buildContext(connection);
        String accessToken;
        try {
            accessToken = lwaClient.fetchAccessToken(ctx);
        } catch (Exception e) {
            log.warn("Failed to obtain access token for store {} entity sync: {}",
                    storeId, e.getMessage());
            return EntitySyncResult.failure(storeId, "Token refresh failed: " + e.getMessage());
        }

        SyncCounts counts = new SyncCounts();

        try {
            // 1. Fetch all entity metadata from Amazon Ads with NO open DB transaction.
            //    Holding a DB connection across remote HTTP calls is deliberately avoided.
            List<JsonNode> campaigns = fetchCampaigns(ctx, accessToken);
            List<JsonNode> adGroups = fetchAdGroups(ctx, accessToken);
            List<JsonNode> keywords = fetchKeywords(ctx, accessToken);

            // 2. Persist each entity-type batch in its own short transaction. Ordering
            //    matters: campaigns commit first so ad groups can resolve internal campaign
            //    ids, and ad groups commit before keywords resolve ad-group ids.
            transactionTemplate.executeWithoutResult(
                    status -> persistCampaigns(storeId, campaigns, counts));
            transactionTemplate.executeWithoutResult(
                    status -> persistAdGroups(storeId, adGroups, counts));
            transactionTemplate.executeWithoutResult(
                    status -> persistKeywords(storeId, keywords, counts));

            log.info("Entity sync completed for store {}: {} campaigns, {} ad groups, {} keywords",
                    storeId, counts.campaignsSynced, counts.adGroupsSynced, counts.keywordsSynced);

            return EntitySyncResult.success(storeId, counts.campaignsSynced, counts.adGroupsSynced,
                    counts.keywordsSynced, counts.mappingsCreated, counts.mappingsUpdated);

        } catch (Exception e) {
            log.error("Entity sync failed for store {}: {}", storeId, e.getMessage(), e);
            return EntitySyncResult.failure(storeId, e.getMessage());
        }
    }

    // ── Transactional persistence (each batch commits independently) ─────────────

    private void persistCampaigns(UUID storeId, List<JsonNode> campaigns, SyncCounts counts) {
        for (JsonNode campaignNode : campaigns) {
            UpsertMappingResult result = syncCampaign(storeId, campaignNode);
            counts.campaignsSynced++;
            if (result.created) counts.mappingsCreated++;
            if (result.updated) counts.mappingsUpdated++;
        }
    }

    private void persistAdGroups(UUID storeId, List<JsonNode> adGroups, SyncCounts counts) {
        for (JsonNode adGroupNode : adGroups) {
            UpsertMappingResult result = syncAdGroup(storeId, adGroupNode);
            counts.adGroupsSynced++;
            if (result.created) counts.mappingsCreated++;
            if (result.updated) counts.mappingsUpdated++;
        }
    }

    private void persistKeywords(UUID storeId, List<JsonNode> keywords, SyncCounts counts) {
        for (JsonNode keywordNode : keywords) {
            UpsertMappingResult result = syncKeyword(storeId, keywordNode);
            counts.keywordsSynced++;
            if (result.created) counts.mappingsCreated++;
            if (result.updated) counts.mappingsUpdated++;
        }
    }

    @Override
    @Transactional
    public void updateMappingAfterWrite(UUID storeId, String internalEntityType,
                                        UUID internalEntityId, String externalEntityId) {
        ExternalEntityMappingEntity existing = findMappingByInternal(
                storeId, internalEntityType, internalEntityId);

        if (existing != null) {
            // Update existing mapping with the Amazon-assigned id (Req 14.5)
            existing.setExternalEntityId(externalEntityId);
            existing.setLastSyncedAt(LocalDateTime.now());
            mappingMapper.updateById(existing);
            log.info("Updated external mapping for {} {} with Amazon id {}",
                    internalEntityType, internalEntityId, externalEntityId);
        } else {
            // Create a new mapping for locally-created entity
            ExternalEntityMappingEntity mapping = ExternalEntityMappingEntity.builder()
                    .storeId(storeId)
                    .platform(PLATFORM)
                    .internalEntityType(internalEntityType)
                    .internalEntityId(internalEntityId)
                    .externalEntityType(internalEntityType)
                    .externalEntityId(externalEntityId)
                    .externalData("{\"origin\":\"local\"}")
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
            mappingMapper.insert(mapping);
            log.info("Created external mapping for locally-created {} {} -> Amazon {}",
                    internalEntityType, internalEntityId, externalEntityId);
        }
    }

    // ── Amazon Ads API calls ─────────────────────────────────────────────────────

    List<JsonNode> fetchCampaigns(ConnectionContext ctx, String accessToken) {
        String url = host(ctx) + "/sp/campaigns/list";
        return fetchEntityList(ctx, url, accessToken);
    }

    List<JsonNode> fetchAdGroups(ConnectionContext ctx, String accessToken) {
        String url = host(ctx) + "/sp/adGroups/list";
        return fetchEntityList(ctx, url, accessToken);
    }

    List<JsonNode> fetchKeywords(ConnectionContext ctx, String accessToken) {
        String url = host(ctx) + "/sp/keywords/list";
        return fetchEntityList(ctx, url, accessToken);
    }

    private List<JsonNode> fetchEntityList(ConnectionContext ctx, String url, String accessToken) {
        List<JsonNode> results = new ArrayList<>();
        // Safety cap on the number of pages, in case a misbehaving API keeps
        // returning a nextToken indefinitely.
        int maxPages = Math.max(1, ENTITY_LIST_MAX_RESULTS / ENTITY_LIST_PAGE_SIZE);
        String nextToken = null;
        try {
            for (int page = 0; page < maxPages; page++) {
                String requestBody = buildListRequestBody(nextToken, ENTITY_LIST_PAGE_SIZE);
                String body = http.post()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Amazon-Advertising-API-ClientId", ctx.credential("clientId"))
                        .header("Amazon-Advertising-API-Scope", ctx.credential("profileId"))
                        .header("Accept", "application/vnd.spCampaign.v3+json")
                        .header("Content-Type", "application/vnd.spCampaign.v3+json")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(body);
                if (root == null) {
                    break;
                }

                // Amazon Ads list endpoints typically return arrays or {campaigns:[...]}
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        results.add(node);
                    }
                } else if (root.isObject()) {
                    // Try common response wrapper field names
                    for (String field : List.of("campaigns", "adGroups", "keywords", "items")) {
                        JsonNode items = root.get(field);
                        if (items != null && items.isArray()) {
                            for (JsonNode node : items) {
                                results.add(node);
                            }
                            break;
                        }
                    }
                }

                // Follow pagination: v3 list endpoints return an optional nextToken
                // on the response root. Stop once it is absent or blank.
                nextToken = extractNextToken(root);
                if (nextToken == null || nextToken.isBlank()) {
                    break;
                }
            }
            return results;
        } catch (RestClientResponseException e) {
            log.warn("Amazon Ads entity list call failed (HTTP {}): {}",
                    e.getStatusCode().value(), e.getMessage());
            return results;
        } catch (Exception e) {
            log.warn("Amazon Ads entity list call failed: {}", e.getMessage());
            return results;
        }
    }

    private String buildListRequestBody(String nextToken, int pageSize) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("maxResults", pageSize);
        if (nextToken != null && !nextToken.isBlank()) {
            body.put("nextToken", nextToken);
        }
        return body.toString();
    }

    private static String extractNextToken(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode tokenNode = root.get("nextToken");
        if (tokenNode == null || tokenNode.isNull()) {
            return null;
        }
        return tokenNode.asText(null);
    }

    // ── Entity upsert logic ─────────────────────────────────────────────────────

    private UpsertMappingResult syncCampaign(UUID storeId, JsonNode node) {
        String externalId = textField(node, "campaignId");
        String name = textField(node, "name");
        String state = textField(node, "state");
        String campaignType = textField(node, "campaignType", "targetingType");

        if (externalId == null || externalId.isBlank()) {
            return UpsertMappingResult.NOOP;
        }

        // Check if mapping already exists
        ExternalEntityMappingEntity existingMapping = findMappingByExternal(
                storeId, "campaign", externalId);

        if (existingMapping != null) {
            // Update existing campaign entity
            UUID internalId = existingMapping.getInternalEntityId();
            CampaignEntity campaign = campaignMapper.selectById(internalId);
            if (campaign != null) {
                if (name != null) campaign.setName(name);
                if (state != null) campaign.setState(state);
                if (campaignType != null) campaign.setCampaignType(campaignType);
                campaign.setAmazonCampaignId(externalId);
                campaignMapper.updateById(campaign);
            }
            existingMapping.setLastSyncedAt(LocalDateTime.now());
            existingMapping.setExternalData(node.toString());
            mappingMapper.updateById(existingMapping);
            return UpsertMappingResult.UPDATED;
        } else {
            // Create new campaign entity with origin amazon_import (Req 14.4)
            CampaignEntity campaign = CampaignEntity.builder()
                    .storeId(storeId)
                    .name(name != null ? name : "Campaign " + externalId)
                    .campaignType(campaignType)
                    .state(state)
                    .status(mapState(state))
                    .externalId(externalId)
                    .amazonCampaignId(externalId)
                    .origin(ORIGIN_AMAZON_IMPORT)
                    .build();
            campaignMapper.insert(campaign);

            // Create external entity mapping
            ExternalEntityMappingEntity mapping = ExternalEntityMappingEntity.builder()
                    .storeId(storeId)
                    .platform(PLATFORM)
                    .internalEntityType("campaign")
                    .internalEntityId(campaign.getId())
                    .externalEntityType("campaign")
                    .externalEntityId(externalId)
                    .externalData(node.toString())
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
            mappingMapper.insert(mapping);
            return UpsertMappingResult.CREATED;
        }
    }

    private UpsertMappingResult syncAdGroup(UUID storeId, JsonNode node) {
        String externalId = textField(node, "adGroupId");
        String name = textField(node, "name");
        String state = textField(node, "state");
        String externalCampaignId = textField(node, "campaignId");

        if (externalId == null || externalId.isBlank()) {
            return UpsertMappingResult.NOOP;
        }

        ExternalEntityMappingEntity existingMapping = findMappingByExternal(
                storeId, "ad_group", externalId);

        if (existingMapping != null) {
            UUID internalId = existingMapping.getInternalEntityId();
            AdGroupEntity adGroup = adGroupMapper.selectById(internalId);
            if (adGroup != null) {
                if (name != null) adGroup.setName(name);
                if (state != null) adGroup.setStatus(mapState(state));
                adGroup.setExternalId(externalId);
                adGroupMapper.updateById(adGroup);
            }
            existingMapping.setLastSyncedAt(LocalDateTime.now());
            existingMapping.setExternalData(node.toString());
            mappingMapper.updateById(existingMapping);
            return UpsertMappingResult.UPDATED;
        } else {
            // Resolve internal campaign id from external campaign id
            UUID internalCampaignId = resolveInternalId(storeId, "campaign", externalCampaignId);

            AdGroupEntity adGroup = AdGroupEntity.builder()
                    .storeId(storeId)
                    .campaignId(internalCampaignId)
                    .name(name != null ? name : "Ad Group " + externalId)
                    .status(mapState(state))
                    .externalId(externalId)
                    .build();
            adGroupMapper.insert(adGroup);

            ExternalEntityMappingEntity mapping = ExternalEntityMappingEntity.builder()
                    .storeId(storeId)
                    .platform(PLATFORM)
                    .internalEntityType("ad_group")
                    .internalEntityId(adGroup.getId())
                    .externalEntityType("ad_group")
                    .externalEntityId(externalId)
                    .externalData(node.toString())
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
            mappingMapper.insert(mapping);
            return UpsertMappingResult.CREATED;
        }
    }

    private UpsertMappingResult syncKeyword(UUID storeId, JsonNode node) {
        String externalId = textField(node, "keywordId");
        String keywordText = textField(node, "keywordText");
        String matchType = textField(node, "matchType");
        String state = textField(node, "state");
        String externalAdGroupId = textField(node, "adGroupId");
        String externalCampaignId = textField(node, "campaignId");

        if (externalId == null || externalId.isBlank()) {
            return UpsertMappingResult.NOOP;
        }

        ExternalEntityMappingEntity existingMapping = findMappingByExternal(
                storeId, "keyword", externalId);

        if (existingMapping != null) {
            UUID internalId = existingMapping.getInternalEntityId();
            KeywordEntity keyword = keywordMapper.selectById(internalId);
            if (keyword != null) {
                if (keywordText != null) keyword.setKeywordText(keywordText);
                if (matchType != null) keyword.setMatchType(matchType.toLowerCase());
                if (state != null) keyword.setStatus(mapState(state));
                keyword.setExternalId(externalId);
                // Update bid if present
                JsonNode bidNode = node.get("bid");
                if (bidNode != null && bidNode.isNumber()) {
                    keyword.setBid(bidNode.decimalValue());
                }
                keywordMapper.updateById(keyword);
            }
            existingMapping.setLastSyncedAt(LocalDateTime.now());
            existingMapping.setExternalData(node.toString());
            mappingMapper.updateById(existingMapping);
            return UpsertMappingResult.UPDATED;
        } else {
            UUID internalCampaignId = resolveInternalId(storeId, "campaign", externalCampaignId);
            UUID internalAdGroupId = resolveInternalId(storeId, "ad_group", externalAdGroupId);

            KeywordEntity keyword = KeywordEntity.builder()
                    .storeId(storeId)
                    .campaignId(internalCampaignId)
                    .adGroupId(internalAdGroupId)
                    .keywordText(keywordText != null ? keywordText : "")
                    .matchType(matchType != null ? matchType.toLowerCase() : "broad")
                    .status(mapState(state))
                    .externalId(externalId)
                    .build();

            // Set bid if present
            JsonNode bidNode = node.get("bid");
            if (bidNode != null && bidNode.isNumber()) {
                keyword.setBid(bidNode.decimalValue());
            }

            keywordMapper.insert(keyword);

            ExternalEntityMappingEntity mapping = ExternalEntityMappingEntity.builder()
                    .storeId(storeId)
                    .platform(PLATFORM)
                    .internalEntityType("keyword")
                    .internalEntityId(keyword.getId())
                    .externalEntityType("keyword")
                    .externalEntityId(externalId)
                    .externalData(node.toString())
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
            mappingMapper.insert(mapping);
            return UpsertMappingResult.CREATED;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private PlatformConnectionEntity findAmazonAdsConnection(UUID storeId) {
        LambdaQueryWrapper<PlatformConnectionEntity> query = new LambdaQueryWrapper<>();
        query.eq(PlatformConnectionEntity::getStoreId, storeId)
                .eq(PlatformConnectionEntity::getPlatform, PLATFORM)
                .eq(PlatformConnectionEntity::getStatus, ConnectionStatus.CONNECTED);
        return connectionMapper.selectOne(query);
    }

    private ConnectionContext buildContext(PlatformConnectionEntity conn) {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("refreshToken", conn.getRefreshTokenEncrypted());
        credentials.put("clientId", extractConfigField(conn, "clientId"));
        credentials.put("clientSecret", extractConfigField(conn, "clientSecret"));
        credentials.put("profileId", conn.getProfileId());
        credentials.put("region", conn.getRegion());
        return new ConnectionContext(conn.getId(), conn.getStoreId(), PLATFORM, credentials);
    }

    private String extractConfigField(PlatformConnectionEntity conn, String field) {
        if (conn.getConfigEncrypted() == null) return null;
        try {
            JsonNode config = objectMapper.readTree(conn.getConfigEncrypted());
            JsonNode value = config.get(field);
            return value != null && !value.isNull() ? value.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    ExternalEntityMappingEntity findMappingByExternal(UUID storeId, String entityType,
                                                      String externalId) {
        if (externalId == null) return null;
        LambdaQueryWrapper<ExternalEntityMappingEntity> query = new LambdaQueryWrapper<>();
        query.eq(ExternalEntityMappingEntity::getStoreId, storeId)
                .eq(ExternalEntityMappingEntity::getPlatform, PLATFORM)
                .eq(ExternalEntityMappingEntity::getExternalEntityType, entityType)
                .eq(ExternalEntityMappingEntity::getExternalEntityId, externalId);
        return mappingMapper.selectOne(query);
    }

    private ExternalEntityMappingEntity findMappingByInternal(UUID storeId,
                                                              String entityType,
                                                              UUID internalId) {
        LambdaQueryWrapper<ExternalEntityMappingEntity> query = new LambdaQueryWrapper<>();
        query.eq(ExternalEntityMappingEntity::getStoreId, storeId)
                .eq(ExternalEntityMappingEntity::getPlatform, PLATFORM)
                .eq(ExternalEntityMappingEntity::getInternalEntityType, entityType)
                .eq(ExternalEntityMappingEntity::getInternalEntityId, internalId);
        return mappingMapper.selectOne(query);
    }

    private UUID resolveInternalId(UUID storeId, String entityType, String externalId) {
        if (externalId == null) return null;
        ExternalEntityMappingEntity mapping = findMappingByExternal(storeId, entityType, externalId);
        return mapping != null ? mapping.getInternalEntityId() : null;
    }

    private String host(ConnectionContext ctx) {
        String region = ctx.credential("region");
        if (region == null) region = "na";
        return switch (region.toLowerCase()) {
            case "eu" -> "https://advertising-api-eu.amazon.com";
            case "fe" -> "https://advertising-api-fp.amazon.com";
            default -> "https://advertising-api.amazon.com";
        };
    }

    private static String mapState(String amazonState) {
        if (amazonState == null) return "enabled";
        return switch (amazonState.toUpperCase()) {
            case "ENABLED" -> "enabled";
            case "PAUSED" -> "paused";
            case "ARCHIVED" -> "archived";
            default -> "enabled";
        };
    }

    private static String textField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode v = node.get(field);
            if (v != null && !v.isNull()) {
                String s = v.asText(null);
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }

    /** Simple result enum for upsert tracking. */
    enum UpsertMappingResult {
        CREATED(true, false),
        UPDATED(false, true),
        NOOP(false, false);

        final boolean created;
        final boolean updated;

        UpsertMappingResult(boolean created, boolean updated) {
            this.created = created;
            this.updated = updated;
        }
    }

    /** Mutable accumulator so per-batch persistence lambdas can update shared counts. */
    private static final class SyncCounts {
        int campaignsSynced = 0;
        int adGroupsSynced = 0;
        int keywordsSynced = 0;
        int mappingsCreated = 0;
        int mappingsUpdated = 0;
    }
}
