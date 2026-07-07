package com.adpilot.modules.user.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.UserContextService;
import com.adpilot.modules.user.entity.Permission;
import com.adpilot.modules.user.entity.Role;
import com.adpilot.modules.user.mapper.PermissionMapper;
import com.adpilot.modules.user.mapper.RoleMapper;
import com.adpilot.modules.user.mapper.RolePermissionMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status-code tests for {@link RoleServiceImpl} business errors (optimization M1):
 * duplicate role code -> 409, missing role -> 404. These previously surfaced as
 * raw {@code RuntimeException} (HTTP 500); they now carry the correct HTTP status
 * via {@link BusinessException}.
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplStatusCodeTest {

    @Mock
    private RoleMapper roleMapper;
    @Mock
    private RolePermissionMapper rolePermissionMapper;
    @Mock
    private PermissionMapper permissionMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private UserContextService userContextService;
    @Mock
    private com.adpilot.modules.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private RoleServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticate a caller whose organization is {@code orgId}. */
    private static void authenticateOrg(UUID orgId) {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("admin@example.com")
                .orgId(orgId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** Authenticate a caller with explicit role codes and effective permissions. */
    private static void authenticate(UUID orgId, Set<String> roles, List<String> permissions) {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("admin@example.com")
                .orgId(orgId.toString())
                .roles(roles)
                .permissions(permissions)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    // Duplicate role code within the org is a conflict -> 409.
    @Test
    void createRole_duplicateCode_throws409() {
        Role role = Role.builder()
                .orgId(UUID.randomUUID())
                .code("admin")
                .name("Administrator")
                .build();
        when(roleMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = catchThrowableOfType(
                () -> service.createRole(role), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("ROLE_CODE_EXISTS");
        // The duplicate is never persisted.
        verify(roleMapper, never()).insert(any());
    }

    // Updating a non-existent role is a not-found -> 404.
    @Test
    void updateRole_missing_throws404() {
        UUID roleId = UUID.randomUUID();
        when(roleMapper.selectById(roleId)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> service.updateRole(roleId.toString(), new Role()), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("ROLE_NOT_FOUND");
        verify(roleMapper, never()).updateById(any());
    }

    // Sanity: a business error is not a generic exception surfacing as 500.
    @Test
    void createRole_duplicateCode_isBusinessException() {
        Role role = Role.builder().orgId(UUID.randomUUID()).code("dup").name("Dup").build();
        when(roleMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.createRole(role))
                .isInstanceOf(BusinessException.class);
    }

    // --- Multi-tenant isolation -------------------------------------------------------

    // createRole ignores a client-supplied orgId and stamps the caller's org from context.
    @Test
    void createRole_ignoresBodyOrgId_usesCallerOrg() {
        UUID callerOrg = UUID.randomUUID();
        UUID attackerOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        Role role = Role.builder().orgId(attackerOrg).code("ops").name("Ops").build();
        when(roleMapper.selectCount(any())).thenReturn(0L);

        service.createRole(role);

        // The persisted role belongs to the caller's org, never the body-supplied org.
        assertThat(role.getOrgId()).isEqualTo(callerOrg);
        verify(roleMapper).insert(role);
    }

    // updateRole on a role owned by another org is rejected as 404 (existence hidden).
    @Test
    void updateRole_crossOrg_throws404() {
        UUID callerOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID roleId = UUID.randomUUID();
        Role existing = Role.builder().id(roleId).orgId(otherOrg).code("x").name("X").build();
        when(roleMapper.selectById(roleId)).thenReturn(existing);

        BusinessException ex = catchThrowableOfType(
                () -> service.updateRole(roleId.toString(), new Role()), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("ROLE_NOT_FOUND");
        verify(roleMapper, never()).updateById(any());
    }

    // updateRole on a same-org role proceeds normally (legitimate behavior preserved).
    @Test
    void updateRole_sameOrg_updates() {
        UUID callerOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID roleId = UUID.randomUUID();
        Role existing = Role.builder().id(roleId).orgId(callerOrg).code("ops").name("Old").build();
        when(roleMapper.selectById(roleId)).thenReturn(existing);
        Role patch = Role.builder().name("New Name").build();

        Role result = service.updateRole(roleId.toString(), patch);

        assertThat(result.getName()).isEqualTo("New Name");
        verify(roleMapper).updateById(existing);
    }

    // assignPermissions on a cross-org role is rejected as 404 and never mutates the set.
    @Test
    void assignPermissions_crossOrg_throws404() {
        UUID callerOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID roleId = UUID.randomUUID();
        Role existing = Role.builder().id(roleId).orgId(otherOrg).code("x").name("X").build();
        when(roleMapper.selectById(roleId)).thenReturn(existing);

        BusinessException ex = catchThrowableOfType(
                () -> service.assignPermissions(roleId.toString(), List.of(UUID.randomUUID().toString())),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("ROLE_NOT_FOUND");
        verify(rolePermissionMapper, never()).delete(any());
        verify(rolePermissionMapper, never()).insert(any());
    }

    // getRolePermissions on a cross-org role is rejected as 404 (no permission disclosure).
    @Test
    void getRolePermissions_crossOrg_throws404() {
        UUID callerOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID roleId = UUID.randomUUID();
        Role existing = Role.builder().id(roleId).orgId(otherOrg).code("x").name("X").build();
        when(roleMapper.selectById(roleId)).thenReturn(existing);

        BusinessException ex = catchThrowableOfType(
                () -> service.getRolePermissions(roleId.toString()), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("ROLE_NOT_FOUND");
    }

    // --- assignPermissions: privilege-escalation guard (C1) ---------------------------

    // A role:manage holder cannot attach a permission they do not themselves hold (403),
    // and the role's permission set is left untouched when the request is rejected.
    @Test
    void assignPermissions_permissionCallerLacks_throws403() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("manager"), List.of("role:view"));
        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).orgId(callerOrg).code("ops").name("Ops").build();
        when(roleMapper.selectById(roleId)).thenReturn(role);

        UUID permId = UUID.randomUUID();
        when(permissionMapper.selectById(permId)).thenReturn(
                Permission.builder().id(permId).code("role:manage").build());

        BusinessException ex = catchThrowableOfType(
                () -> service.assignPermissions(roleId.toString(), List.of(permId.toString())),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        assertThat(ex.getCode()).isEqualTo("PERMISSION_ESCALATION");
        verify(rolePermissionMapper, never()).delete(any());
        verify(rolePermissionMapper, never()).insert(any());
    }

    // A caller may attach a permission they themselves hold (legitimate path preserved).
    @Test
    void assignPermissions_permissionCallerHolds_succeeds() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("manager"), List.of("role:view", "role:manage"));
        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).orgId(callerOrg).code("ops").name("Ops").build();
        when(roleMapper.selectById(roleId)).thenReturn(role);

        UUID permId = UUID.randomUUID();
        when(permissionMapper.selectById(permId)).thenReturn(
                Permission.builder().id(permId).code("role:manage").build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.assignPermissions(roleId.toString(), List.of(permId.toString()));

        verify(rolePermissionMapper).delete(any());
        verify(rolePermissionMapper, times(1)).insert(any());
    }

    // A super_admin caller already holds everything and may attach any permission.
    @Test
    void assignPermissions_superAdminCaller_succeeds() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("super_admin"), List.of());
        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).orgId(callerOrg).code("ops").name("Ops").build();
        when(roleMapper.selectById(roleId)).thenReturn(role);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.assignPermissions(roleId.toString(), List.of(UUID.randomUUID().toString()));

        verify(rolePermissionMapper).delete(any());
        verify(rolePermissionMapper, times(1)).insert(any());
    }
}
