package com.adpilot.modules.user.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.user.entity.Department;
import com.adpilot.modules.user.entity.LoginLog;
import com.adpilot.modules.user.entity.Role;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.entity.UserDepartment;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DepartmentMapper;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.RoleMapper;
import com.adpilot.modules.user.mapper.UserDepartmentMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.adpilot.modules.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Returns the profile of the currently authenticated user with REAL data
 * (roles, permissions, department, phone, recent logins) resolved from the DB.
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserService userService;
    private final LoginLogMapper loginLogMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final DepartmentMapper departmentMapper;

    @GetMapping
    public ApiResponse<Map<String, Object>> getProfile() {
        CurrentUser current = SecurityUtils.getCurrentUser();
        String userId = current.getUserId();
        User user = userService.getUserById(userId).orElse(null);

        Map<String, Object> body = new HashMap<>();
        body.put("name", user != null ? user.getName() : current.getName());
        body.put("email", user != null ? user.getEmail() : current.getEmail());
        body.put("phone", user != null && user.getPhone() != null ? user.getPhone() : "");
        body.put("department", resolveDepartmentName(userId));

        // Roles (real, from user_roles -> roles)
        List<Role> roles = resolveRoles(userId);
        List<Map<String, String>> roleObjs = roles.stream().map(r -> {
            Map<String, String> ro = new HashMap<>();
            ro.put("id", r.getId() != null ? r.getId().toString() : "");
            ro.put("name", r.getName());
            ro.put("description", r.getDescription() != null ? r.getDescription() : "");
            return ro;
        }).collect(Collectors.toList());
        body.put("roles", roleObjs);
        body.put("role", roles.isEmpty() ? (current.getRoles() != null && !current.getRoles().isEmpty()
                ? current.getRoles().iterator().next() : "") : roles.get(0).getName());

        // Permissions (real, aggregated from roles)
        body.put("permissions", userService.getUserPermissions(userId));

        // Recent logins
        body.put("recentLogins", resolveRecentLogins(userId));

        return ApiResponse.ok(body);
    }

    private List<Role> resolveRoles(String userId) {
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>();
        w.eq(UserRole::getUserId, UUID.fromString(userId));
        List<UUID> roleIds = userRoleMapper.selectList(w).stream()
                .map(UserRole::getRoleId).distinct().collect(Collectors.toList());
        if (roleIds.isEmpty()) return new ArrayList<>();
        return roleMapper.selectBatchIds(roleIds);
    }

    private String resolveDepartmentName(String userId) {
        try {
            LambdaQueryWrapper<UserDepartment> w = new LambdaQueryWrapper<>();
            w.eq(UserDepartment::getUserId, UUID.fromString(userId)).last("LIMIT 1");
            UserDepartment ud = userDepartmentMapper.selectOne(w);
            if (ud == null || ud.getDepartmentId() == null) return "";
            Department dept = departmentMapper.selectById(ud.getDepartmentId());
            return dept != null ? dept.getName() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private List<Map<String, String>> resolveRecentLogins(String userId) {
        List<Map<String, String>> recent = new ArrayList<>();
        try {
            LambdaQueryWrapper<LoginLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(LoginLog::getUserId, UUID.fromString(userId));
            wrapper.orderByDesc(LoginLog::getCreatedAt);
            Page<LoginLog> logs = loginLogMapper.selectPage(new Page<>(1, 5), wrapper);
            for (LoginLog log : logs.getRecords()) {
                Map<String, String> m = new HashMap<>();
                m.put("id", log.getId() != null ? log.getId().toString() : "");
                m.put("time", log.getCreatedAt() != null ? log.getCreatedAt().format(TS) : "");
                m.put("ip", log.getIpAddress() != null ? log.getIpAddress() : "");
                m.put("browser", log.getUserAgent() != null ? log.getUserAgent() : "");
                m.put("status", log.getLoginStatus() != null ? log.getLoginStatus() : "");
                recent.add(m);
            }
        } catch (Exception ignored) {
        }
        return recent;
    }
}
