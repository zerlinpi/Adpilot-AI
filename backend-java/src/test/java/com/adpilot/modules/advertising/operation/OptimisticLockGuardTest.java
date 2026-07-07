package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OptimisticLockGuard}: the explicit {@code WHERE id = ? AND version = ?}
 * guarded update with {@code SET ..., version = version + 1}, and the zero-row →
 * {@link VersionConflictException} rejection (Req 5.4, 5.5).
 */
class OptimisticLockGuardTest {

    private final OptimisticLockGuard guard = new OptimisticLockGuard();

    @Test
    void guardedUpdate_appliesFieldSetsAndBumpsVersionGuardedOnExpectedVersion() {
        @SuppressWarnings("unchecked")
        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(1);

        UUID id = UUID.randomUUID();
        guard.guardedUpdate(mapper, "campaign", id, 7L,
                w -> w.set("status", "paused"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<CampaignEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());

        UpdateWrapper<CampaignEntity> wrapper = captor.getValue();
        // SET clause carries both the field mutation and the version bump.
        assertThat(wrapper.getSqlSet())
                .contains("status")
                .contains("version = version + 1");
        // WHERE clause guards on id and the expected version.
        String where = wrapper.getTargetSql();
        assertThat(where).contains("id").contains("version");
    }

    @Test
    void guardedUpdate_zeroRowsThrowsVersionConflictWithContext() {
        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);

        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> guard.guardedUpdate(mapper, "campaign", id, 3L,
                w -> w.set("status", "paused")))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> {
                    VersionConflictException vce = (VersionConflictException) ex;
                    assertThat(vce.getEntityType()).isEqualTo("campaign");
                    assertThat(vce.getEntityId()).isEqualTo(id);
                    assertThat(vce.getExpectedVersion()).isEqualTo(3L);
                    assertThat(vce.getCode()).isEqualTo(VersionConflictException.ERROR_CODE);
                    assertThat(vce.getStatus()).isEqualTo(409);
                });
    }

    @Test
    void guardVersion_bumpsVersionOnlyAndSucceedsWhenRowMatches() {
        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(1);

        UUID id = UUID.randomUUID();
        guard.guardVersion(mapper, "campaign", id, 0L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<CampaignEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).isEqualTo("version = version + 1");
    }

    @Test
    void guardVersion_zeroRowsThrowsVersionConflict() {
        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> guard.guardVersion(mapper, "keyword", UUID.randomUUID(), 9L))
                .isInstanceOf(VersionConflictException.class);
    }

    @Test
    void guardedUpdate_rejectsNullMapperAndNullId() {
        CampaignMapper mapper = mock(CampaignMapper.class);
        assertThatThrownBy(() -> guard.guardVersion(null, "campaign", UUID.randomUUID(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.guardVersion(mapper, "campaign", null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
