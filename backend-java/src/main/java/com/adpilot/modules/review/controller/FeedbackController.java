package com.adpilot.modules.review.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.review.entity.CustomerReviewEntity;
import com.adpilot.modules.review.mapper.CustomerReviewMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
 * Seller/product feedback view, consumed by the frontend Feedback page.
 * Feedback is derived from customer reviews (rating-based type), since the
 * domain treats low-star reviews as negative feedback to feed back into
 * Listing AI.
 */
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Store-only scope target shared by review-domain entities (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    private final CustomerReviewMapper customerReviewMapper;
    private final DataScopeService dataScopeService;

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @GetMapping
    @RequirePermission("review:view")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String asin,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        QueryWrapper<CustomerReviewEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            try { wrapper.eq("store_id", java.util.UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { /* invalid id -> skip store filter */ }
        }
        // Constrain every result to the caller's store data-scope so feedback for
        // stores outside the caller's tenant is never returned (cross-tenant BOLA).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        if (asin != null && !asin.isBlank()) {
            wrapper.eq("asin", asin);
        }
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            wrapper.eq("status", status);
        }
        if (search != null && !search.isBlank()) {
            wrapper.and(w -> w.like("title", search)
                    .or().like("review_text", search));
        }
        // Map feedback type to rating bands.
        if ("positive".equalsIgnoreCase(type)) {
            wrapper.ge("rating", 4);
        } else if ("negative".equalsIgnoreCase(type)) {
            wrapper.le("rating", 2);
        }
        wrapper.orderByDesc("review_date");

        Page<CustomerReviewEntity> result =
                customerReviewMapper.selectPage(new Page<>(page, pageSize), wrapper);

        List<Map<String, Object>> items = new ArrayList<>();
        for (CustomerReviewEntity r : result.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId() != null ? r.getId().toString() : null);
            m.put("asin", r.getAsin());
            m.put("sku", r.getSku());
            m.put("rating", r.getRating());
            m.put("title", r.getTitle());
            m.put("content", r.getReviewText());
            m.put("reviewer", r.getReviewerName());
            m.put("type", (r.getRating() != null && r.getRating() <= 2) ? "negative" : "positive");
            m.put("status", r.getStatus());
            m.put("date", r.getReviewDate() != null ? r.getReviewDate().format(TS) : null);
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
