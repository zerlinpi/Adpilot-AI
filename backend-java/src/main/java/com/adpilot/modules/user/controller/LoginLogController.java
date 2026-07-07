package com.adpilot.modules.user.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.modules.user.entity.LoginLog;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to authentication login logs, consumed by the frontend
 * Login Logs page.
 */
@RestController
@RequestMapping("/api/login-logs")
@RequiredArgsConstructor
public class LoginLogController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LoginLogMapper loginLogMapper;

    @GetMapping
    public ApiResponse<Map<String, Object>> listLoginLogs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<LoginLog> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            wrapper.eq(LoginLog::getLoginStatus, status);
        }
        if (search != null && !search.isBlank()) {
            wrapper.and(w -> w.like(LoginLog::getEmail, search)
                    .or().like(LoginLog::getUsername, search)
                    .or().like(LoginLog::getIpAddress, search));
        }
        wrapper.orderByDesc(LoginLog::getCreatedAt);

        Page<LoginLog> result = loginLogMapper.selectPage(new Page<>(page, pageSize), wrapper);

        // Map to the field names the frontend expects.
        List<Map<String, Object>> items = new ArrayList<>();
        for (LoginLog l : result.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", l.getId() != null ? l.getId().toString() : null);
            m.put("loginTime", l.getCreatedAt() != null ? l.getCreatedAt().format(TS) : "");
            m.put("email", l.getEmail() != null ? l.getEmail() : "");
            m.put("userName", l.getUsername() != null ? l.getUsername() : (l.getEmail() != null ? l.getEmail() : "-"));
            m.put("status", l.getLoginStatus());
            m.put("ip", l.getIpAddress() != null ? l.getIpAddress() : "");
            m.put("browser", l.getUserAgent() != null ? l.getUserAgent() : "");
            m.put("failReason", l.getFailureReason());
            items.add(m);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("total", result.getTotal());
        body.put("page", page);
        body.put("pageSize", pageSize);
        return ApiResponse.ok(body);
    }
}
