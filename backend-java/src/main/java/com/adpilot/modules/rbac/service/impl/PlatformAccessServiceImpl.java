package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.entity.AccountPlatformAccessEntity;
import com.adpilot.modules.rbac.mapper.AccountPlatformAccessMapper;
import com.adpilot.modules.rbac.service.PlatformAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves an account's Platform_Access from {@code account_platform_access}
 * rows, applying the super-administrator bypass (platform-workspace-rbac
 * Req 12, 15.4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAccessServiceImpl implements PlatformAccessService {

    /** Role code that bypasses all RBAC_Model restrictions (Req 15.4). */
    private static final String SUPER_ADMIN_ROLE = "super_admin";

    private final AccountPlatformAccessMapper accountPlatformAccessMapper;

    @Override
    public Set<PlatformFamily> accessibleFamilies(CurrentUser user) {
        // Super-admin enters every Nav_Block without an explicit grant (Req 15.4).
        if (isSuperAdmin(user)) {
            return EnumSet.allOf(PlatformFamily.class);
        }

        if (user == null || user.getUserId() == null) {
            return EnumSet.noneOf(PlatformFamily.class);
        }

        UUID userId = UUID.fromString(user.getUserId());
        List<AccountPlatformAccessEntity> rows = accountPlatformAccessMapper.selectList(
                new LambdaQueryWrapper<AccountPlatformAccessEntity>()
                        .eq(AccountPlatformAccessEntity::getUserId, userId));

        Set<PlatformFamily> families = EnumSet.noneOf(PlatformFamily.class);
        for (AccountPlatformAccessEntity row : rows) {
            try {
                families.add(PlatformFamily.fromCode(row.getPlatformFamily()));
            } catch (IllegalArgumentException ex) {
                // Defensive: ignore an unrecognised persisted code rather than
                // failing the whole access check.
                log.warn("Ignoring unknown platform_family '{}' for user {}",
                        row.getPlatformFamily(), userId);
            }
        }
        return families;
    }

    @Override
    public boolean hasAccess(CurrentUser user, PlatformFamily family) {
        if (family == null) {
            return false;
        }
        if (isSuperAdmin(user)) {
            return true;
        }
        return accessibleFamilies(user).contains(family);
    }

    private boolean isSuperAdmin(CurrentUser user) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().contains(SUPER_ADMIN_ROLE);
    }
}
