package com.adpilot.modules.user.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.UserContextService;
import com.adpilot.modules.auth.service.PasswordPolicyService;
import com.adpilot.modules.user.entity.Permission;
import com.adpilot.modules.user.entity.Role;
import com.adpilot.modules.user.entity.RolePermission;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.PermissionMapper;
import com.adpilot.modules.user.mapper.RoleMapper;
import com.adpilot.modules.user.mapper.RolePermissionMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.adpilot.modules.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security tests for {@link UserServiceImpl}: anti mass-assignment on create and
 * cross-organization isolation (IDOR) on update.
 *
 * <p>createUser must ignore any client-supplied orgId (stamping the caller's org from
 * the authenticated context) and force the security-managed fields to their safe
 * server-controlled values regardless of the request body. updateUser must reject a
 * target belonging to another org as 404 (existence hidden).</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplSecurityTest {

    @Mock private UserRepository userRepository;
    @Mock private UserMapper userMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private RolePermissionMapper rolePermissionMapper;
    @Mock private PermissionMapper permissionMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserContextService userContextService;
    @Mock private PasswordPolicyService passwordPolicyService;
    @Mock private com.adpilot.modules.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private UserServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

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

    // createUser ignores body orgId + all client-supplied security fields.
    @Test
    void createUser_stampsCallerOrg_andResetsSecurityFields() {
        UUID callerOrg = UUID.randomUUID();
        UUID attackerOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);

        User body = User.builder()
                .orgId(attackerOrg)                 // must be ignored
                .name("New User")
                .email("new@example.com")
                .passwordHash("StrongPassw0rd!")
                .status("super_admin")              // must be forced to "active"
                .failedLoginCount(99)               // must be forced to 0
                .lockedUntil(LocalDateTime.now())   // must be forced to null
                .twofaEnabled(true)                 // must be forced to false
                .twofaSecret("STOLEN-SECRET")       // must be forced to null
                .lastLoginAt(LocalDateTime.now())   // must be forced to null
                .build();

        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("StrongPassw0rd!")).thenReturn("HASHED");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = service.createUser(body);

        assertThat(saved.getOrgId()).isEqualTo(callerOrg);
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getFailedLoginCount()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
        assertThat(saved.getTwofaEnabled()).isFalse();
        assertThat(saved.getTwofaSecret()).isNull();
        assertThat(saved.getLastLoginAt()).isNull();
        // Password is still hashed, never stored in plaintext.
        assertThat(saved.getPasswordHash()).isEqualTo("HASHED");
    }

    // updateUser on a user owned by another org is rejected as 404 and never persisted.
    @Test
    void updateUser_crossOrg_throws404() {
        UUID callerOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID userId = UUID.randomUUID();
        User existing = User.builder()
                .id(userId).orgId(otherOrg).name("Victim").email("victim@example.com").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(existing));

        BusinessException ex = catchThrowableOfType(
                () -> service.updateUser(userId.toString(), User.builder().name("Hacked").build()),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        verify(userRepository, never()).save(any());
    }

    // updateUser on a same-org user proceeds (legitimate behavior preserved).
    @Test
    void updateUser_sameOrg_updates() {
        UUID callerOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID userId = UUID.randomUUID();
        User existing = User.builder()
                .id(userId).orgId(callerOrg).name("Old").email("user@example.com").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.updateUser(userId.toString(), User.builder().name("New Name").build());

        assertThat(result.getName()).isEqualTo("New Name");
        // orgId is not moved across tenants by the generic update path.
        assertThat(result.getOrgId()).isEqualTo(callerOrg);
        verify(userRepository).save(existing);
    }

    // --- assignRole: privilege-escalation guard (C1) -------------------------------

    // assignRole targeting a user in another org is rejected as 404 and never mutates
    // the role set (the user's existence in another org is not revealed).
    @Test
    void assignRole_crossOrgTarget_throws404() {
        UUID callerOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).orgId(otherOrg).email("victim@example.com").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        BusinessException ex = catchThrowableOfType(
                () -> service.assignRole(targetId.toString(), List.of(UUID.randomUUID().toString())),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        verify(userRoleMapper, never()).delete(any());
        verify(userRoleMapper, never()).insert(any());
    }

    // A tenant caller cannot grant a role that carries a permission the caller lacks:
    // the granted role's permission set must be a subset of the caller's own (403).
    @Test
    void assignRole_roleWithPermissionCallerLacks_throws403() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("manager"), List.of("user:view"));
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).orgId(callerOrg).email("t@example.com").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).orgId(callerOrg).code("ops").name("Ops").build();
        when(roleMapper.selectById(roleId)).thenReturn(role);

        UUID permId = UUID.randomUUID();
        when(rolePermissionMapper.selectList(any())).thenReturn(
                List.of(RolePermission.builder().roleId(roleId).permissionId(permId).build()));
        when(permissionMapper.selectList(any())).thenReturn(
                List.of(Permission.builder().id(permId).code("user:manage").build()));

        BusinessException ex = catchThrowableOfType(
                () -> service.assignRole(targetId.toString(), List.of(roleId.toString())),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        assertThat(ex.getCode()).isEqualTo("ROLE_ESCALATION");
        // Nothing is mutated when the request is rejected.
        verify(userRoleMapper, never()).delete(any());
        verify(userRoleMapper, never()).insert(any());
    }

    // A tenant caller may never grant the system super_admin role through this path.
    @Test
    void assignRole_superAdminRoleThroughTenantPath_throws403() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("manager"), List.of("user:view", "user:manage"));
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).orgId(callerOrg).email("t@example.com").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        UUID roleId = UUID.randomUUID();
        Role superAdmin = Role.builder().id(roleId).orgId(callerOrg).code("super_admin").name("Super").build();
        when(roleMapper.selectById(roleId)).thenReturn(superAdmin);

        BusinessException ex = catchThrowableOfType(
                () -> service.assignRole(targetId.toString(), List.of(roleId.toString())),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        assertThat(ex.getCode()).isEqualTo("ROLE_ESCALATION");
        verify(userRoleMapper, never()).insert(any());
    }

    // A super_admin caller already holds everything, so any org role is assignable.
    @Test
    void assignRole_superAdminCaller_succeeds() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("super_admin"), List.of());
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).orgId(callerOrg).email("t@example.com").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).orgId(callerOrg).code("ops").name("Ops").build();
        when(roleMapper.selectById(roleId)).thenReturn(role);

        service.assignRole(targetId.toString(), List.of(roleId.toString()));

        verify(userRoleMapper).delete(any());
        verify(userRoleMapper, times(1)).insert(any());
        verify(userContextService).invalidate(targetId.toString());
    }

    // A tenant caller may grant a role whose permissions are a subset of their own.
    @Test
    void assignRole_subsetPermissions_succeeds() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("manager"), List.of("user:view", "user:manage"));
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).orgId(callerOrg).email("t@example.com").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).orgId(callerOrg).code("viewer").name("Viewer").build();
        when(roleMapper.selectById(roleId)).thenReturn(role);

        UUID permId = UUID.randomUUID();
        when(rolePermissionMapper.selectList(any())).thenReturn(
                List.of(RolePermission.builder().roleId(roleId).permissionId(permId).build()));
        when(permissionMapper.selectList(any())).thenReturn(
                List.of(Permission.builder().id(permId).code("user:view").build()));

        service.assignRole(targetId.toString(), List.of(roleId.toString()));

        verify(userRoleMapper, times(1)).insert(any());
        verify(userContextService).invalidate(targetId.toString());
    }

    // --- Forensic audit trail (additive) ----------------------------------------------

    // On a successful role assignment, an "ASSIGN_ROLE" audit entry is written for the
    // "user" entity keyed by the target user id (the audit write is additive and does
    // not alter the assignment behavior).
    @Test
    void assignRole_success_writesAssignRoleAuditLog() {
        UUID callerOrg = UUID.randomUUID();
        authenticate(callerOrg, Set.of("super_admin"), List.of());
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).orgId(callerOrg).email("t@example.com").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        UUID roleId = UUID.randomUUID();
        Role role = Role.builder().id(roleId).orgId(callerOrg).code("ops").name("Ops").build();
        when(roleMapper.selectById(roleId)).thenReturn(role);

        service.assignRole(targetId.toString(), List.of(roleId.toString()));

        // The audit entry uses the exact action/entity strings and the target user id.
        verify(auditLogService).createLog(
                any(), any(), eq("ASSIGN_ROLE"), eq("user"), eq(targetId), any());
    }
}
