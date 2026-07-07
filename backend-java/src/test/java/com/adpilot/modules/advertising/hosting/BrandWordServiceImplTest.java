package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.dto.BrandWordCreateRequest;
import com.adpilot.modules.advertising.vo.BrandWordVo;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BrandWordServiceImpl}.
 *
 * <p>Covers brand-word CRUD: add/list/delete, match-type validation, cache invalidation,
 * audit logging, and cross-store delete protection.</p>
 *
 * <p>Validates: Requirements 22.1, 22.2, 22.6.</p>
 */
@DisplayName("BrandWordServiceImpl")
class BrandWordServiceImplTest {

    private BrandWordMapper brandWordMapper;
    private BrandWordProtectionService protectionService;
    private StoreMapper storeMapper;
    private AuditLogService auditLogService;
    private BrandWordServiceImpl service;

    private UUID storeId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        brandWordMapper = mock(BrandWordMapper.class);
        protectionService = mock(BrandWordProtectionService.class);
        storeMapper = mock(StoreMapper.class);
        auditLogService = mock(AuditLogService.class);
        service = new BrandWordServiceImpl(brandWordMapper, protectionService, storeMapper, auditLogService);

        storeId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        when(storeMapper.selectById(storeId)).thenReturn(
                StoreEntity.builder().id(storeId).orgId(orgId).build());
    }

    @Nested
    @DisplayName("addBrandWord")
    class AddBrandWord {

        @Test
        @DisplayName("inserts, defaults match_type to exact, invalidates cache and audits")
        void addsWord() {
            BrandWordCreateRequest req = BrandWordCreateRequest.builder().word("Acme").build();

            BrandWordVo vo = service.addBrandWord(storeId, req, null);

            assertThat(vo.getWord()).isEqualTo("Acme");
            assertThat(vo.getMatchType()).isEqualTo("exact");

            ArgumentCaptor<BrandWordEntity> captor = ArgumentCaptor.forClass(BrandWordEntity.class);
            verify(brandWordMapper).insert(captor.capture());
            assertThat(captor.getValue().getStoreId()).isEqualTo(storeId);
            assertThat(captor.getValue().getMatchType()).isEqualTo("exact");

            verify(protectionService).invalidateCache(storeId);
            verify(auditLogService).createLog(any(), eq(orgId), eq("CREATE"), eq("brand_word"), any(), any());
        }

        @Test
        @DisplayName("accepts the contains match type (case-insensitive)")
        void acceptsContains() {
            BrandWordCreateRequest req = BrandWordCreateRequest.builder()
                    .word("brand").matchType("CONTAINS").build();
            BrandWordVo vo = service.addBrandWord(storeId, req, null);
            assertThat(vo.getMatchType()).isEqualTo("contains");
        }

        @Test
        @DisplayName("rejects a blank word")
        void rejectsBlank() {
            BrandWordCreateRequest req = BrandWordCreateRequest.builder().word("   ").build();
            assertThatThrownBy(() -> service.addBrandWord(storeId, req, null))
                    .isInstanceOf(BusinessException.class);
            verify(brandWordMapper, never()).insert(any());
        }

        @Test
        @DisplayName("rejects an invalid match type")
        void rejectsInvalidMatchType() {
            BrandWordCreateRequest req = BrandWordCreateRequest.builder()
                    .word("brand").matchType("fuzzy").build();
            assertThatThrownBy(() -> service.addBrandWord(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_PARAMETER");
        }
    }

    @Nested
    @DisplayName("deleteBrandWord")
    class DeleteBrandWord {

        @Test
        @DisplayName("deletes, invalidates cache and audits when owned by the store")
        void deletesOwned() {
            UUID wordId = UUID.randomUUID();
            when(brandWordMapper.selectById(wordId)).thenReturn(BrandWordEntity.builder()
                    .id(wordId).storeId(storeId).word("Acme").matchType("exact").build());

            service.deleteBrandWord(storeId, wordId, null);

            verify(brandWordMapper).deleteById(wordId);
            verify(protectionService).invalidateCache(storeId);
            verify(auditLogService).createLog(any(), eq(orgId), eq("DELETE"), eq("brand_word"), any(), any());
        }

        @Test
        @DisplayName("rejects deletion of a word that does not exist")
        void rejectsMissing() {
            UUID wordId = UUID.randomUUID();
            when(brandWordMapper.selectById(wordId)).thenReturn(null);
            assertThatThrownBy(() -> service.deleteBrandWord(storeId, wordId, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_BRAND_WORD_NOT_FOUND");
            verify(brandWordMapper, never()).deleteById(any(UUID.class));
        }

        @Test
        @DisplayName("rejects deletion of a word belonging to another store")
        void rejectsCrossStore() {
            UUID wordId = UUID.randomUUID();
            when(brandWordMapper.selectById(wordId)).thenReturn(BrandWordEntity.builder()
                    .id(wordId).storeId(UUID.randomUUID()).word("Acme").build());
            assertThatThrownBy(() -> service.deleteBrandWord(storeId, wordId, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_BRAND_WORD_NOT_FOUND");
            verify(brandWordMapper, never()).deleteById(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("listBrandWords")
    class ListBrandWords {

        @Test
        @DisplayName("maps entities to view objects")
        void mapsEntities() {
            when(brandWordMapper.selectByStoreId(storeId)).thenReturn(List.of(
                    BrandWordEntity.builder().id(UUID.randomUUID()).storeId(storeId)
                            .word("Acme").matchType("exact").createdAt(LocalDateTime.now()).build(),
                    BrandWordEntity.builder().id(UUID.randomUUID()).storeId(storeId)
                            .word("Globex").matchType("contains").createdAt(LocalDateTime.now()).build()));

            List<BrandWordVo> result = service.listBrandWords(storeId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(BrandWordVo::getWord).containsExactly("Acme", "Globex");
        }
    }
}
