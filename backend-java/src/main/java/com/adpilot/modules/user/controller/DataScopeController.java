package com.adpilot.modules.user.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.Role;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.RoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read access to role data-scope configurations, consumed by the frontend
 * Data Scopes management page. Returns a UI-friendly shape (roleName + array
 * fields) so the page can render without null-array crashes.
 */
@RestController
@RequestMapping("/api/data-scopes")
@RequiredArgsConstructor
public class DataScopeController {

    private final DataScopeMapper dataScopeMapper;
    private final RoleMapper roleMapper;

    @GetMapping
    @RequirePermission("role:view")
    public ApiResponse<List<Map<String, Object>>> listDataScopes() {
        // Multi-tenant isolation: the org is derived from the authenticated security
        // context. Only data-scopes whose role belongs to the caller's org are exposed,
        // so this endpoint never discloses scope configurations from other organizations.
        String orgId = SecurityUtils.getCurrentOrgId();

        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getOrgId, UUID.fromString(orgId));
        List<Role> orgRoles = roleMapper.selectList(roleWrapper);

        Map<UUID, String> roleNamesById = new HashMap<>();
        for (Role r : orgRoles) {
            if (r.getId() != null) {
                roleNamesById.put(r.getId(), r.getName());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        if (roleNamesById.isEmpty()) {
            return ApiResponse.ok(result);
        }

        LambdaQueryWrapper<DataScope> scopeWrapper = new LambdaQueryWrapper<>();
        scopeWrapper.in(DataScope::getRoleId, roleNamesById.keySet());
        List<DataScope> scopes = dataScopeMapper.selectList(scopeWrapper);

        for (DataScope s : scopes) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId() != null ? s.getId().toString() : null);
            m.put("roleId", s.getRoleId() != null ? s.getRoleId().toString() : null);
            m.put("roleName", resolveRoleName(s.getRoleId(), roleNamesById));
            m.put("scopeType", s.getScopeType());
            m.put("shops", s.getStoreIds() != null ? s.getStoreIds() : new ArrayList<>());
            m.put("sites", new ArrayList<>());
            m.put("departments", new ArrayList<>());
            m.put("productIds", s.getProductIds() != null ? s.getProductIds() : new ArrayList<>());
            result.add(m);
        }
        return ApiResponse.ok(result);
    }

    private String resolveRoleName(UUID roleId, Map<UUID, String> roleNamesById) {
        if (roleId == null) return "";
        String name = roleNamesById.get(roleId);
        return name != null ? name : roleId.toString();
    }
}
