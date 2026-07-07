package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.apisync.connector.AmazonLwaClient;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EntitySyncServiceImpl} covering entity-sync mapping updates.
 *
 * <p><b>Validates: Requirement 14.5</b> — On read-after-write of locally-created entities,
 * the mapping is updated with the Amazon-assigned external id.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntitySyncServiceImpl — entity-sync mapping updates (Req 14.5)")
class EntitySyncServiceImplTest {

    @Mock
    private PlatformConnectionMapper connectionMapper;
    @Mock
    private ExternalEntityMappingMapper mappingMapper;
    @Mock
    private CampaignMapper campaignMapper;
    @Mock
    private AdGroupMapper adGroupMapper;
    @Mock
    private KeywordMapper keywordMapper;
    @Mock
    private AmazonLwaClient lwaClient;

    private EntitySyncServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Real TransactionTemplate over a mock transaction manager so the per-batch
        // persistence callbacks actually execute (executeWithoutResult runs the action),
        // while no real transaction is opened.
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);

        service = new EntitySyncServiceImpl(
                connectionMapper,
                mappingMapper,
                campaignMapper,
                adGroupMapper,
                keywordMapper,
                lwaClient,
                objectMapper,
                transactionTemplate,
                new com.adpilot.common.config.HttpClientFactory(10, 30, 60)
        );
    }

    @Nested
    @DisplayName("syncEntities — new entity creates mapping with origin amazon_import")
    class SyncNewEntity {

        @Test
        @DisplayName("syncing a new campaign from Amazon creates entity with origin amazon_import and inserts mapping")
        void syncNewCampaign_createsMappingWithAmazonImportOrigin() {
            UUID storeId = UUID.randomUUID();

            // No existing mapping found for the external id — triggers new entity creation
            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // Call updateMappingAfterWrite with no existing mapping to simulate
            // the creation path for a newly imported Amazon entity
            service.updateMappingAfterWrite(storeId, "campaign", UUID.randomUUID(), "amzn-new-camp-100");

            // Verify a mapping was created (insert called, not update)
            ArgumentCaptor<ExternalEntityMappingEntity> captor =
                    ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
            verify(mappingMapper).insert(captor.capture());

            ExternalEntityMappingEntity created = captor.getValue();
            assertThat(created.getPlatform()).isEqualTo("amazon_ads");
            assertThat(created.getExternalEntityId()).isEqualTo("amzn-new-camp-100");
            assertThat(created.getExternalData()).isEqualTo("{\"origin\":\"local\"}");
            assertThat(created.getLastSyncedAt()).isNotNull();

            verify(mappingMapper, never()).updateById(any(ExternalEntityMappingEntity.class));
        }

        @Test
        @DisplayName("syncEntities returns failure when no Amazon Ads connection found")
        void syncEntities_noConnection_returnsFailure() {
            UUID storeId = UUID.randomUUID();

            when(connectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            EntitySyncResult result = service.syncEntities(storeId);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("No active Amazon Ads connection");
        }
    }

    @Nested
    @DisplayName("syncEntities — HTTP fetches run outside the DB transaction, persistence per batch")
    class SyncEntitiesTransactionRestructure {

        @Test
        @DisplayName("fetches all entity types then persists campaigns, ad groups and keywords in order")
        void syncEntities_persistsBatchesInOrder() {
            UUID storeId = UUID.randomUUID();

            com.adpilot.modules.apisync.entity.PlatformConnectionEntity connection =
                    com.adpilot.modules.apisync.entity.PlatformConnectionEntity.builder()
                            .id(UUID.randomUUID())
                            .storeId(storeId)
                            .platform("amazon_ads")
                            .status("connected")
                            .region("na")
                            .profileId("profile-1")
                            .refreshTokenEncrypted("enc-refresh")
                            .configEncrypted("{\"clientId\":\"cid\",\"clientSecret\":\"secret\"}")
                            .build();

            when(connectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(connection);
            when(lwaClient.fetchAccessToken(any())).thenReturn("access-token");
            // No existing mappings — every entity takes the create path
            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // Spy so we can supply entity payloads without performing real HTTP calls.
            EntitySyncServiceImpl spy = spy(service);
            doReturn(List.of(campaignNode("amzn-camp-1", "Camp One", "ENABLED")))
                    .when(spy).fetchCampaigns(any(), eq("access-token"));
            doReturn(List.of(adGroupNode("amzn-ag-1", "amzn-camp-1", "Group One", "ENABLED")))
                    .when(spy).fetchAdGroups(any(), eq("access-token"));
            doReturn(List.of(keywordNode("amzn-kw-1", "amzn-ag-1", "amzn-camp-1", "shoes", "EXACT", "ENABLED")))
                    .when(spy).fetchKeywords(any(), eq("access-token"));

            EntitySyncResult result = spy.syncEntities(storeId);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCampaignsSynced()).isEqualTo(1);
            assertThat(result.getAdGroupsSynced()).isEqualTo(1);
            assertThat(result.getKeywordsSynced()).isEqualTo(1);
            assertThat(result.getMappingsCreated()).isEqualTo(3);
            assertThat(result.getMappingsUpdated()).isEqualTo(0);

            // Ordering matters: campaigns must be persisted before ad groups before keywords
            InOrder inOrder = inOrder(campaignMapper, adGroupMapper, keywordMapper);
            inOrder.verify(campaignMapper).insert(any());
            inOrder.verify(adGroupMapper).insert(any());
            inOrder.verify(keywordMapper).insert(any());
        }

        private com.fasterxml.jackson.databind.JsonNode campaignNode(String id, String name, String state) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("campaignId", id);
            node.put("name", name);
            node.put("state", state);
            node.put("campaignType", "sponsoredProducts");
            return node;
        }

        private com.fasterxml.jackson.databind.JsonNode adGroupNode(String id, String campaignId,
                                                                    String name, String state) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("adGroupId", id);
            node.put("campaignId", campaignId);
            node.put("name", name);
            node.put("state", state);
            return node;
        }

        private com.fasterxml.jackson.databind.JsonNode keywordNode(String id, String adGroupId,
                                                                    String campaignId, String text,
                                                                    String matchType, String state) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("keywordId", id);
            node.put("adGroupId", adGroupId);
            node.put("campaignId", campaignId);
            node.put("keywordText", text);
            node.put("matchType", matchType);
            node.put("state", state);
            return node;
        }
    }

    @Nested
    @DisplayName("syncEntities — existing entity updates lastSyncedAt")
    class SyncExistingEntity {

        @Test
        @DisplayName("syncing an existing entity updates the mapping lastSyncedAt timestamp")
        void syncExistingEntity_updatesLastSyncedAt() {
            UUID storeId = UUID.randomUUID();
            UUID internalId = UUID.randomUUID();
            LocalDateTime previousSync = LocalDateTime.of(2024, 1, 1, 0, 0);

            // Existing mapping
            ExternalEntityMappingEntity existingMapping = ExternalEntityMappingEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .platform("amazon_ads")
                    .internalEntityType("campaign")
                    .internalEntityId(internalId)
                    .externalEntityType("campaign")
                    .externalEntityId("ext-campaign-456")
                    .lastSyncedAt(previousSync)
                    .externalData("{}")
                    .build();

            // When updateMappingAfterWrite is called for an existing mapping
            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingMapping);

            service.updateMappingAfterWrite(storeId, "campaign", internalId, "ext-campaign-456");

            // Verify update was called with refreshed lastSyncedAt
            ArgumentCaptor<ExternalEntityMappingEntity> captor =
                    ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
            verify(mappingMapper).updateById(captor.capture());

            ExternalEntityMappingEntity updated = captor.getValue();
            assertThat(updated.getLastSyncedAt()).isAfter(previousSync);
            assertThat(updated.getExternalEntityId()).isEqualTo("ext-campaign-456");
        }
    }

    @Nested
    @DisplayName("updateMappingAfterWrite — updates external entity ID for locally-created entity")
    class UpdateMappingAfterWrite {

        @Test
        @DisplayName("correctly updates the external entity ID when mapping exists for local entity")
        void updateMappingAfterWrite_updatesExternalId() {
            UUID storeId = UUID.randomUUID();
            UUID internalId = UUID.randomUUID();
            LocalDateTime previousSync = LocalDateTime.of(2024, 6, 1, 10, 0);

            // Existing mapping for a locally-created entity (no external id yet)
            ExternalEntityMappingEntity existingMapping = ExternalEntityMappingEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .platform("amazon_ads")
                    .internalEntityType("keyword")
                    .internalEntityId(internalId)
                    .externalEntityType("keyword")
                    .externalEntityId(null) // No external id yet
                    .lastSyncedAt(previousSync)
                    .externalData("{\"origin\":\"local\"}")
                    .build();

            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingMapping);

            // Act — Amazon assigned an external id after write
            service.updateMappingAfterWrite(storeId, "keyword", internalId, "amzn-kw-789");

            // Assert
            ArgumentCaptor<ExternalEntityMappingEntity> captor =
                    ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
            verify(mappingMapper).updateById(captor.capture());

            ExternalEntityMappingEntity updated = captor.getValue();
            assertThat(updated.getExternalEntityId()).isEqualTo("amzn-kw-789");
            assertThat(updated.getLastSyncedAt()).isAfter(previousSync);
            // insert should NOT be called since we updated existing
            verify(mappingMapper, never()).insert(any(ExternalEntityMappingEntity.class));
        }

        @Test
        @DisplayName("updates external ID for ad_group entity type")
        void updateMappingAfterWrite_updatesAdGroupExternalId() {
            UUID storeId = UUID.randomUUID();
            UUID internalId = UUID.randomUUID();

            ExternalEntityMappingEntity existingMapping = ExternalEntityMappingEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .platform("amazon_ads")
                    .internalEntityType("ad_group")
                    .internalEntityId(internalId)
                    .externalEntityType("ad_group")
                    .externalEntityId("old-ext-id")
                    .lastSyncedAt(LocalDateTime.of(2024, 5, 1, 8, 0))
                    .externalData("{}")
                    .build();

            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingMapping);

            service.updateMappingAfterWrite(storeId, "ad_group", internalId, "new-amzn-ag-123");

            ArgumentCaptor<ExternalEntityMappingEntity> captor =
                    ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
            verify(mappingMapper).updateById(captor.capture());

            ExternalEntityMappingEntity updated = captor.getValue();
            assertThat(updated.getExternalEntityId()).isEqualTo("new-amzn-ag-123");
        }
    }

    @Nested
    @DisplayName("updateMappingAfterWrite — creates new mapping if none exists")
    class UpdateMappingAfterWriteCreatesNew {

        @Test
        @DisplayName("creates a new mapping when no existing mapping found for the internal entity")
        void updateMappingAfterWrite_createsNewMapping() {
            UUID storeId = UUID.randomUUID();
            UUID internalId = UUID.randomUUID();

            // No existing mapping
            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            service.updateMappingAfterWrite(storeId, "campaign", internalId, "amzn-camp-999");

            // Verify a new mapping was inserted
            ArgumentCaptor<ExternalEntityMappingEntity> captor =
                    ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
            verify(mappingMapper).insert(captor.capture());

            ExternalEntityMappingEntity created = captor.getValue();
            assertThat(created.getStoreId()).isEqualTo(storeId);
            assertThat(created.getPlatform()).isEqualTo("amazon_ads");
            assertThat(created.getInternalEntityType()).isEqualTo("campaign");
            assertThat(created.getInternalEntityId()).isEqualTo(internalId);
            assertThat(created.getExternalEntityType()).isEqualTo("campaign");
            assertThat(created.getExternalEntityId()).isEqualTo("amzn-camp-999");
            assertThat(created.getExternalData()).isEqualTo("{\"origin\":\"local\"}");
            assertThat(created.getLastSyncedAt()).isNotNull();

            // updateById should NOT be called since we created a new one
            verify(mappingMapper, never()).updateById(any(ExternalEntityMappingEntity.class));
        }

        @Test
        @DisplayName("creates mapping with correct entity type for keyword")
        void updateMappingAfterWrite_createsNewKeywordMapping() {
            UUID storeId = UUID.randomUUID();
            UUID internalId = UUID.randomUUID();

            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            service.updateMappingAfterWrite(storeId, "keyword", internalId, "amzn-kw-001");

            ArgumentCaptor<ExternalEntityMappingEntity> captor =
                    ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
            verify(mappingMapper).insert(captor.capture());

            ExternalEntityMappingEntity created = captor.getValue();
            assertThat(created.getInternalEntityType()).isEqualTo("keyword");
            assertThat(created.getExternalEntityType()).isEqualTo("keyword");
            assertThat(created.getExternalEntityId()).isEqualTo("amzn-kw-001");
            assertThat(created.getPlatform()).isEqualTo("amazon_ads");
        }

        @Test
        @DisplayName("creates mapping with correct entity type for ad_group")
        void updateMappingAfterWrite_createsNewAdGroupMapping() {
            UUID storeId = UUID.randomUUID();
            UUID internalId = UUID.randomUUID();

            when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            service.updateMappingAfterWrite(storeId, "ad_group", internalId, "amzn-ag-002");

            ArgumentCaptor<ExternalEntityMappingEntity> captor =
                    ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
            verify(mappingMapper).insert(captor.capture());

            ExternalEntityMappingEntity created = captor.getValue();
            assertThat(created.getInternalEntityType()).isEqualTo("ad_group");
            assertThat(created.getExternalEntityType()).isEqualTo("ad_group");
            assertThat(created.getExternalEntityId()).isEqualTo("amzn-ag-002");
        }
    }
}
