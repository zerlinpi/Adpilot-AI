package com.adpilot.common.security;

import com.adpilot.modules.audit.entity.AuditLogEntity;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditServiceImpl auditService;

    @Captor
    private ArgumentCaptor<AuditLogEntity> entityCaptor;

    private final String userId = UUID.randomUUID().toString();
    private final String orgId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        CurrentUser user = CurrentUser.builder()
                .userId(userId)
                .orgId(orgId)
                .email("op@example.com")
                .roles(Set.of("operator"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsPermitDecisionWithUserIdentityAndOperation() {
        when(auditLogMapper.insert(entityCaptor.capture())).thenReturn(1);

        auditService.recordAuthorizationDecision("campaign:update", true);

        AuditLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getAction()).isEqualTo(AuditServiceImpl.ACTION_PERMIT);
        assertThat(entity.getEntityType()).isEqualTo(AuditServiceImpl.ENTITY_TYPE);
        assertThat(entity.getUserId()).isEqualTo(UUID.fromString(userId));
        assertThat(entity.getOrgId()).isEqualTo(UUID.fromString(orgId));
        assertThat(entity.getNewData()).contains("campaign:update").contains("permit");
    }

    @Test
    void recordsDenyDecisionWithUserIdentityAndOperation() {
        when(auditLogMapper.insert(entityCaptor.capture())).thenReturn(1);

        auditService.recordDeny("campaign:delete");

        AuditLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getAction()).isEqualTo(AuditServiceImpl.ACTION_DENY);
        assertThat(entity.getUserId()).isEqualTo(UUID.fromString(userId));
        assertThat(entity.getNewData()).contains("campaign:delete").contains("deny");
    }

    @Test
    void recordsDecisionWithoutAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        when(auditLogMapper.insert(entityCaptor.capture())).thenReturn(1);

        auditService.recordPermit("public:read");

        AuditLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getUserId()).isNull();
        assertThat(entity.getOrgId()).isNull();
        assertThat(entity.getAction()).isEqualTo(AuditServiceImpl.ACTION_PERMIT);
        verify(auditLogMapper).insert(entity);
    }
}
