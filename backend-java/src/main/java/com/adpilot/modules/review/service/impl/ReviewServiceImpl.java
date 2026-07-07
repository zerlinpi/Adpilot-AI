package com.adpilot.modules.review.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.review.entity.CustomerReviewEntity;
import com.adpilot.modules.review.entity.ListingQualityCheckEntity;
import com.adpilot.modules.review.entity.ReviewAlertEntity;
import com.adpilot.modules.review.entity.ReviewResponseTemplateEntity;
import com.adpilot.modules.review.mapper.CustomerReviewMapper;
import com.adpilot.modules.review.mapper.ListingQualityCheckMapper;
import com.adpilot.modules.review.mapper.ReviewAlertMapper;
import com.adpilot.modules.review.mapper.ReviewResponseTemplateMapper;
import com.adpilot.modules.review.service.ReviewService;
import com.adpilot.modules.review.vo.CustomerReviewVo;
import com.adpilot.modules.review.vo.ListingQualityCheckVo;
import com.adpilot.modules.review.vo.ReviewAlertVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final CustomerReviewMapper customerReviewMapper;
    private final ReviewAlertMapper reviewAlertMapper;
    private final ListingQualityCheckMapper listingQualityCheckMapper;
    private final ReviewResponseTemplateMapper reviewResponseTemplateMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Store-only scope target shared by review-domain entities (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    // ---- Reviews ----

    @Override
    public PageResponse<CustomerReviewVo> listReviews(String storeId, int page, int pageSize) {
        Page<CustomerReviewEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<CustomerReviewEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            try { wrapper.eq("store_id", UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<CustomerReviewEntity> result = customerReviewMapper.selectPage(pageParam, wrapper);
        List<CustomerReviewVo> voList = result.getRecords().stream()
                .map(this::toReviewVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public CustomerReviewVo getReviewById(String id) {
        CustomerReviewEntity entity = customerReviewMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("REVIEW_NOT_FOUND", "Review not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toReviewVo(entity);
    }

    @Override
    @Transactional
    public CustomerReviewVo respondToReview(String id, String responseText, String userId) {
        CustomerReviewEntity entity = customerReviewMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("REVIEW_NOT_FOUND", "Review not found: " + id);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        entity.setResponseText(responseText);
        entity.setRespondedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        customerReviewMapper.updateById(entity);
        log.info("Review responded: id={}", id);
        return toReviewVo(entity);
    }

    // ---- Alerts ----

    @Override
    public PageResponse<ReviewAlertVo> listAlerts(String storeId, int page, int pageSize) {
        Page<ReviewAlertEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ReviewAlertEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            try { wrapper.eq("store_id", UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<ReviewAlertEntity> result = reviewAlertMapper.selectPage(pageParam, wrapper);
        List<ReviewAlertVo> voList = result.getRecords().stream()
                .map(this::toAlertVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public ReviewAlertVo resolveAlert(String id, String userId) {
        ReviewAlertEntity entity = reviewAlertMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("ALERT_NOT_FOUND", "Alert not found: " + id);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        entity.setStatus("resolved");
        entity.setResolvedAt(LocalDateTime.now());
        if (userId != null) {
            entity.setAssignedTo(UUID.fromString(userId));
        }
        entity.setUpdatedAt(LocalDateTime.now());

        reviewAlertMapper.updateById(entity);
        log.info("Alert resolved: id={}", id);
        return toAlertVo(entity);
    }

    // ---- Quality Checks ----

    @Override
    public PageResponse<ListingQualityCheckVo> listQualityChecks(String storeId, int page, int pageSize) {
        Page<ListingQualityCheckEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ListingQualityCheckEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            try { wrapper.eq("store_id", UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<ListingQualityCheckEntity> result = listingQualityCheckMapper.selectPage(pageParam, wrapper);
        List<ListingQualityCheckVo> voList = result.getRecords().stream()
                .map(this::toQualityCheckVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public ListingQualityCheckVo getQualityCheck(String id) {
        ListingQualityCheckEntity entity = listingQualityCheckMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("QUALITY_CHECK_NOT_FOUND", "Quality check not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toQualityCheckVo(entity);
    }

    // ---- Response Templates ----

    @Override
    public List<Map<String, Object>> listResponseTemplates(String orgId) {
        LambdaQueryWrapper<ReviewResponseTemplateEntity> wrapper = new LambdaQueryWrapper<>();
        if (orgId != null && !orgId.isEmpty()) {
            wrapper.eq(ReviewResponseTemplateEntity::getOrgId, UUID.fromString(orgId));
        }
        wrapper.orderByDesc(ReviewResponseTemplateEntity::getCreatedAt);

        List<ReviewResponseTemplateEntity> entities = reviewResponseTemplateMapper.selectList(wrapper);
        return entities.stream()
                .map(this::toTemplateMap)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public Map<String, Object> createResponseTemplate(Map<String, Object> dto, String userId) {
        ReviewResponseTemplateEntity entity = ReviewResponseTemplateEntity.builder()
                .orgId(dto.get("orgId") != null ? UUID.fromString(dto.get("orgId").toString()) : null)
                .templateName(dto.get("templateName") != null ? dto.get("templateName").toString() : null)
                .responseType(dto.get("responseType") != null ? dto.get("responseType").toString() : null)
                .ratingMin(dto.get("ratingMin") != null ? Integer.parseInt(dto.get("ratingMin").toString()) : null)
                .ratingMax(dto.get("ratingMax") != null ? Integer.parseInt(dto.get("ratingMax").toString()) : null)
                .responseText(dto.get("responseText") != null ? dto.get("responseText").toString() : null)
                .variables(dto.get("variables") != null ? dto.get("variables").toString() : "[]")
                .status("active")
                .build();

        reviewResponseTemplateMapper.insert(entity);
        log.info("Response template created: id={}, name={}", entity.getId(), entity.getTemplateName());
        return toTemplateMap(entity);
    }

    // ---- VO Mappers ----

    private CustomerReviewVo toReviewVo(CustomerReviewEntity entity) {
        return CustomerReviewVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .reviewId(entity.getReviewId())
                .asin(entity.getAsin())
                .sku(entity.getSku())
                .reviewerName(entity.getReviewerName())
                .rating(entity.getRating())
                .title(entity.getTitle())
                .reviewText(entity.getReviewText())
                .reviewDate(entity.getReviewDate() != null ? entity.getReviewDate().format(FORMATTER) : null)
                .verifiedPurchase(entity.getVerifiedPurchase())
                .helpfulVotes(entity.getHelpfulVotes())
                .sentiment(entity.getSentiment())
                .sentimentScore(entity.getSentimentScore() != null ? entity.getSentimentScore().doubleValue() : null)
                .status(entity.getStatus())
                .responseText(entity.getResponseText())
                .respondedAt(entity.getRespondedAt() != null ? entity.getRespondedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private ReviewAlertVo toAlertVo(ReviewAlertEntity entity) {
        return ReviewAlertVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .reviewId(entity.getReviewId() != null ? entity.getReviewId().toString() : null)
                .alertType(entity.getAlertType())
                .severity(entity.getSeverity())
                .message(entity.getMessage())
                .status(entity.getStatus())
                .assignedTo(entity.getAssignedTo() != null ? entity.getAssignedTo().toString() : null)
                .resolvedAt(entity.getResolvedAt() != null ? entity.getResolvedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private ListingQualityCheckVo toQualityCheckVo(ListingQualityCheckEntity entity) {
        return ListingQualityCheckVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .asin(entity.getAsin())
                .sku(entity.getSku())
                .overallScore(entity.getOverallScore() != null ? entity.getOverallScore().doubleValue() : null)
                .titleScore(entity.getTitleScore() != null ? entity.getTitleScore().doubleValue() : null)
                .bulletScore(entity.getBulletScore() != null ? entity.getBulletScore().doubleValue() : null)
                .descriptionScore(entity.getDescriptionScore() != null ? entity.getDescriptionScore().doubleValue() : null)
                .imageScore(entity.getImageScore() != null ? entity.getImageScore().doubleValue() : null)
                .keywordScore(entity.getKeywordScore() != null ? entity.getKeywordScore().doubleValue() : null)
                .issues(entity.getIssues())
                .recommendations(entity.getRecommendations())
                .checkedAt(entity.getCheckedAt() != null ? entity.getCheckedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private Map<String, Object> toTemplateMap(ReviewResponseTemplateEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId().toString());
        map.put("orgId", entity.getOrgId().toString());
        map.put("templateName", entity.getTemplateName());
        map.put("responseType", entity.getResponseType());
        map.put("ratingMin", entity.getRatingMin());
        map.put("ratingMax", entity.getRatingMax());
        map.put("responseText", entity.getResponseText());
        map.put("variables", entity.getVariables());
        map.put("status", entity.getStatus());
        map.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null);
        return map;
    }
}
