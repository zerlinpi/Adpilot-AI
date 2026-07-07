package com.adpilot.modules.user.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.UserContextService;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.auth.service.PasswordPolicyService;
import com.adpilot.modules.user.entity.*;
import com.adpilot.modules.user.mapper.PermissionMapper;
import com.adpilot.modules.user.mapper.RoleMapper;
import com.adpilot.modules.user.mapper.RolePermissionMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.adpilot.modules.user.repository.UserRepository;
import com.adpilot.modules.user.service.UserService;
import com.adpilot.modules.user.validation.UserValidation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserContextService userContextService;
    private final PasswordPolicyService passwordPolicyService;
    private final AuditLogService auditLogService;

    @Override
    public Optional<User> getUserById(String userId) {
        return userRepository.findById(UUID.fromString(userId));
    }

    @Override
    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Override
    public List<User> listUsers(String orgId) {
        return userRepository.findByOrgId(UUID.fromString(orgId));
    }

    @Override
    @Transactional
    public User createUser(User user) {
        // Field-level validation: name 1–255 chars; email single "@" with non-empty
        // local/domain parts, ≤320 chars (Req 5.1, 5.3).
        if (!UserValidation.isValidName(user.getName())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "INVALID_NAME",
                    "Name must be between " + UserValidation.NAME_MIN_LENGTH + " and "
                            + UserValidation.NAME_MAX_LENGTH + " characters");
        }
        if (!UserValidation.isValidEmail(user.getEmail())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "INVALID_EMAIL",
                    "Email must contain a single \"@\" with non-empty local and domain parts and be at most "
                            + UserValidation.EMAIL_MAX_LENGTH + " characters");
        }

        // Reject duplicate email using a case-insensitive comparison (Req 5.4).
        if (userRepository.existsByEmailIgnoreCase(user.getEmail())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT.value(),
                    "DUPLICATE_EMAIL",
                    "Email already in use: " + user.getEmail());
        }

        // Hash password if provided
        if (user.getPasswordHash() != null && !user.getPasswordHash().isEmpty()) {
            // Enforce the configured password-strength policy before hashing (Req 11.1.3).
            passwordPolicyService.validate(user.getPasswordHash());
            user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        }

        // Multi-tenant isolation: the new user's org is always the caller's org derived
        // from the authenticated context; any client-supplied orgId in the body is ignored
        // so a caller cannot create users in another organization. When there is no
        // authenticated principal (a non-interactive/system actor, mirroring
        // OperationServiceImpl), the caller-provided org on the entity is preserved.
        UUID contextOrgId = resolveOrgId();
        if (contextOrgId != null) {
            user.setOrgId(contextOrgId);
        }

        // Anti mass-assignment: security-managed fields are always server-controlled on
        // create and never bound from the request body. A caller cannot pre-activate an
        // account beyond default, bypass lockout counters, or seed a 2FA secret / login
        // timestamp for another identity.
        user.setStatus("active");
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setTwofaEnabled(false);
        user.setTwofaSecret(null);
        user.setLastLoginAt(null);

        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);
        log.info("User created: {}", savedUser.getEmail());

        // Forensic audit trail (additive, best-effort): record the creation without any
        // credential values — only the non-secret email and name are captured.
        Map<String, Object> createDetails = new LinkedHashMap<>();
        createDetails.put("email", savedUser.getEmail());
        createDetails.put("name", savedUser.getName());
        writeAudit("CREATE_USER", savedUser.getId(), createDetails);

        return savedUser;
    }

    @Override
    @Transactional
    public User updateUser(String userId, User user) {
        User existingUser = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Multi-tenant isolation: a caller may only modify a user within their own org.
        // A cross-org target is rejected as 404 (not 403) so this endpoint never reveals
        // the existence of users in other organizations.
        assertSameOrg(existingUser.getOrgId());

        // Update allowed fields only. orgId and the security-managed fields
        // (failedLoginCount, lockedUntil, twofaEnabled, twofaSecret, lastLoginAt) are
        // intentionally NOT bound here so this generic update path cannot be used to
        // move a user across orgs or tamper with lockout/2FA/login state.
        // The names of the fields that actually changed are collected for the audit
        // trail; no field VALUES (least of all the password) are captured.
        List<String> changedFields = new ArrayList<>();
        if (user.getName() != null) {
            existingUser.setName(user.getName());
            changedFields.add("name");
        }
        if (user.getAvatarUrl() != null) {
            existingUser.setAvatarUrl(user.getAvatarUrl());
            changedFields.add("avatarUrl");
        }
        if (user.getPhone() != null) {
            existingUser.setPhone(user.getPhone());
            changedFields.add("phone");
        }
        if (user.getStatus() != null) {
            existingUser.setStatus(user.getStatus());
            changedFields.add("status");
        }
        if (user.getPasswordHash() != null && !user.getPasswordHash().isEmpty()) {
            // Enforce the configured password-strength policy before hashing (Req 11.1.3).
            passwordPolicyService.validate(user.getPasswordHash());
            existingUser.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
            changedFields.add("passwordHash");
        }

        existingUser.setUpdatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(existingUser);
        log.info("User updated: {}", savedUser.getEmail());

        // Forensic audit trail (additive, best-effort): record WHICH fields changed by
        // name only — never the new values.
        Map<String, Object> updateDetails = new LinkedHashMap<>();
        updateDetails.put("changedFields", changedFields);
        writeAudit("UPDATE_USER", savedUser.getId(), updateDetails);

        return savedUser;
    }

    @Override
    @Transactional
    public void assignRole(String userId, List<String> roleIds) {
        UUID userUuid = UUID.fromString(userId);

        // Multi-tenant isolation: the target user must exist within the caller's org.
        // A missing or cross-org target is rejected as 404 so this endpoint never
        // reveals the existence of users in other organizations (mirrors updateUser).
        User target = userRepository.findById(userUuid)
                .orElseThrow(() -> new BusinessException(404, "USER_NOT_FOUND", "User not found"));
        assertSameOrg(target.getOrgId());

        // Privilege-escalation guard: validate the requested roles belong to the caller's
        // org and never grant more than the caller already holds. Performed before any
        // mutation so a rejected request leaves the user's role set untouched.
        validateAssignableRoles(roleIds);

        // Replace the user's role set: delete existing, insert the new ones.
        LambdaQueryWrapper<UserRole> del = new LambdaQueryWrapper<>();
        del.eq(UserRole::getUserId, userUuid);
        userRoleMapper.delete(del);

        if (roleIds != null) {
            for (String roleId : roleIds) {
                if (roleId == null || roleId.isBlank()) continue;
                UserRole ur = UserRole.builder()
                        .userId(userUuid)
                        .roleId(UUID.fromString(roleId))
                        .build();
                userRoleMapper.insert(ur);
            }
        }
        log.info("Assigned {} role(s) to user {}", roleIds != null ? roleIds.size() : 0, userId);

        // Roles changed: drop the cached permission set so it reloads on next request (Req 3.1.5).
        userContextService.invalidate(userId);

        // Forensic audit trail (additive, best-effort): recorded AFTER the successful
        // mutation and cache invalidation. Records the role ids assigned to the target
        // user (ids only — no secret material is involved).
        Map<String, Object> assignDetails = new LinkedHashMap<>();
        assignDetails.put("roleIds", roleIds != null ? roleIds : new ArrayList<>());
        writeAudit("ASSIGN_ROLE", userUuid, assignDetails);
    }

    @Override
    public List<String> getUserPermissions(String userId) {
        log.debug("Getting permissions for user: {}", userId);
        List<UUID> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return new ArrayList<>();
        }

        LambdaQueryWrapper<RolePermission> rpWrapper = new LambdaQueryWrapper<>();
        rpWrapper.in(RolePermission::getRoleId, roleIds);
        List<UUID> permissionIds = rolePermissionMapper.selectList(rpWrapper).stream()
                .map(RolePermission::getPermissionId)
                .distinct()
                .collect(Collectors.toList());
        if (permissionIds.isEmpty()) {
            return new ArrayList<>();
        }

        LambdaQueryWrapper<Permission> pWrapper = new LambdaQueryWrapper<>();
        pWrapper.in(Permission::getId, permissionIds);
        return permissionMapper.selectList(pWrapper).stream()
                .map(Permission::getCode)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public String getUserPrimaryRole(String userId) {
        List<UUID> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return "user";
        }
        // Prefer super_admin if present; otherwise return the first role code.
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        return roles.stream()
                .map(Role::getCode)
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(code -> "super_admin".equals(code) ? 0 : 1))
                .orElse("user");
    }

    /**
     * Validate that every requested role may be legitimately granted by the current
     * caller, guarding against privilege escalation via role assignment:
     *
     * <ol>
     *   <li>Each role must exist AND belong to the caller's organization (a role from
     *       another org is rejected as 404, hiding its existence).</li>
     *   <li>A tenant caller may never grant the {@code super_admin} (system) role.</li>
     *   <li>A tenant caller may only grant a role whose permission set is a subset of
     *       the caller's own effective permissions; granting a role that carries a
     *       permission the caller lacks is rejected as 403.</li>
     * </ol>
     *
     * A {@code super_admin} caller already holds everything, so any role in the org is
     * assignable. Clearing roles (null/empty list) is always allowed. Non-interactive
     * (system) actors with no security context skip these caller-based checks, mirroring
     * the org-stamping behavior of {@code createUser}/{@code updateUser}.
     */
    private void validateAssignableRoles(List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        if (!SecurityUtils.isAuthenticated()) {
            return;
        }

        CurrentUser caller = SecurityUtils.getCurrentUser();
        boolean callerIsSuperAdmin = caller.getRoles() != null
                && caller.getRoles().contains("super_admin");
        UUID callerOrgId = resolveOrgId();
        Set<String> callerPermissions = caller.getPermissions() != null
                ? new HashSet<>(caller.getPermissions())
                : new HashSet<>();

        for (String roleId : roleIds) {
            if (roleId == null || roleId.isBlank()) {
                continue;
            }
            UUID roleUuid;
            try {
                roleUuid = UUID.fromString(roleId.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "INVALID_ROLE", "Invalid role id: " + roleId);
            }

            Role role = roleMapper.selectById(roleUuid);
            // Role must exist and belong to the caller's org (existence hidden otherwise).
            if (role == null || (callerOrgId != null && !callerOrgId.equals(role.getOrgId()))) {
                throw new BusinessException(404, "ROLE_NOT_FOUND", "Role not found");
            }

            // A super_admin caller already has everything: any org role is assignable.
            if (callerIsSuperAdmin) {
                continue;
            }

            // Never allow granting a system/super_admin role through this tenant path.
            if ("super_admin".equals(role.getCode())) {
                throw new BusinessException(403, "ROLE_ESCALATION",
                        "Cannot assign a role that exceeds your own permissions");
            }

            // The granted role's permissions must be a subset of the caller's own.
            List<String> rolePermissions = getRolePermissionCodes(roleUuid);
            if (!callerPermissions.containsAll(rolePermissions)) {
                throw new BusinessException(403, "ROLE_ESCALATION",
                        "Cannot assign a role that exceeds your own permissions");
            }
        }
    }

    /**
     * Resolve the distinct permission codes granted by a single role.
     */
    private List<String> getRolePermissionCodes(UUID roleId) {
        LambdaQueryWrapper<RolePermission> rpWrapper = new LambdaQueryWrapper<>();
        rpWrapper.eq(RolePermission::getRoleId, roleId);
        List<UUID> permissionIds = rolePermissionMapper.selectList(rpWrapper).stream()
                .map(RolePermission::getPermissionId)
                .distinct()
                .collect(Collectors.toList());
        if (permissionIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<Permission> pWrapper = new LambdaQueryWrapper<>();
        pWrapper.in(Permission::getId, permissionIds);
        return permissionMapper.selectList(pWrapper).stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Resolve all role ids assigned to a user.
     */
    private List<UUID> getUserRoleIds(String userId) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId, UUID.fromString(userId));
        return userRoleMapper.selectList(wrapper).stream()
                .map(UserRole::getRoleId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * The caller's organization id taken from the authenticated security context, or
     * {@code null} when there is no interactive principal (a non-interactive/system
     * actor, mirroring {@code OperationServiceImpl#resolveOrgId}). Never trusts a
     * client-supplied org value.
     */
    private UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    /**
     * The acting user's id from the authenticated security context, or {@code null} for a
     * non-interactive/system actor. Used as the actor attribution on audit records.
     */
    private UUID resolveActorId() {
        return parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for a state-changing RBAC action. Auditing is
     * additive and best-effort: a failure here is logged and swallowed so it can never
     * break the business operation. The details map must never carry secret values.
     */
    private void writeAudit(String action, UUID entityId, Map<String, Object> details) {
        try {
            auditLogService.createLog(resolveActorId(), resolveOrgId(), action, "user", entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action={} entityId={}: {}", action, entityId, ex.getMessage());
        }
    }

    /**
     * Enforce that {@code entityOrgId} belongs to the caller's organization. When the
     * caller org cannot be resolved (non-interactive/system actor) the check is skipped
     * so background/system flows keep working. A cross-org mismatch is rejected as 404
     * to avoid leaking the existence of records in other organizations.
     */
    private void assertSameOrg(UUID entityOrgId) {
        UUID callerOrgId = resolveOrgId();
        if (callerOrgId != null && !callerOrgId.equals(entityOrgId)) {
            throw new BusinessException(404, "USER_NOT_FOUND", "User not found");
        }
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
