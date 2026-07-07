package com.adpilot.modules.user.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.modules.user.entity.Permission;
import com.adpilot.modules.user.mapper.PermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only catalog of all system permissions, consumed by the frontend
 * Permissions management page.
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionMapper permissionMapper;

    @GetMapping
    public ApiResponse<List<Permission>> listPermissions() {
        LambdaQueryWrapper<Permission> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(Permission::getModule).orderByAsc(Permission::getAction);
        return ApiResponse.ok(permissionMapper.selectList(wrapper));
    }
}
