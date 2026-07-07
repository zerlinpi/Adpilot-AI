package com.adpilot.modules.upload.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.listing.entity.ListingContentEntity;
import com.adpilot.modules.listing.mapper.ListingContentMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.upload.dto.UploadJobCreateRequest;
import com.adpilot.modules.upload.entity.ProductUploadJobEntity;
import com.adpilot.modules.upload.mapper.ProductUploadJobMapper;
import com.adpilot.modules.upload.publish.IndependentSiteProductPublishService;
import com.adpilot.modules.upload.publish.ProductPublishOutcome;
import com.adpilot.modules.upload.service.ProductUploadService;
import com.adpilot.modules.upload.vo.UploadJobVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductUploadServiceImpl implements ProductUploadService {

    private final ProductUploadJobMapper uploadJobMapper;
    private final ProductMapper productMapper;
    private final ListingContentMapper listingContentMapper;
    private final ObjectMapper objectMapper;
    private final IndependentSiteProductPublishService publishService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> EXPORT_ONLY_UPLOAD_METHODS = Set.of(
            "amazon_flat_file",
            "flat_file",
            "manual_export"
    );
    /**
     * Upload methods that publish the product directly to the store's connected
     * independent-site platform via {@link IndependentSiteProductPublishService}
     * (a real HTTP create call). These are NOT downgraded to manual_export.
     */
    private static final Set<String> DIRECT_PUBLISH_UPLOAD_METHODS = Set.of(
            IndependentSiteProductPublishService.METHOD_SHOPIFY_API,
            IndependentSiteProductPublishService.METHOD_WOOCOMMERCE_API,
            IndependentSiteProductPublishService.METHOD_TIKTOK_API
    );
    /**
     * Direct connectors that are genuinely not implemented yet and are still
     * downgraded to an export. {@code shopify_api}/{@code woocommerce_api}/
     * {@code tiktok_shop_api} were removed from this set once their direct-publish
     * paths went live.
     */
    private static final Set<String> DIRECT_CONNECTORS_NOT_IMPLEMENTED = Set.of(
    );

    @Override
    public PageResponse<UploadJobVo> listJobs(String storeId, String status, int page, int pageSize) {
        Page<ProductUploadJobEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<ProductUploadJobEntity> wrapper = new LambdaQueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq(ProductUploadJobEntity::getStoreId, UUID.fromString(storeId));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(ProductUploadJobEntity::getStatus, status);
        }
        wrapper.orderByDesc(ProductUploadJobEntity::getCreatedAt);

        Page<ProductUploadJobEntity> result = uploadJobMapper.selectPage(pageParam, wrapper);
        List<UploadJobVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());
        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public UploadJobVo getJob(String id) {
        ProductUploadJobEntity entity = uploadJobMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("UPLOAD_JOB_NOT_FOUND", "Upload job not found: " + id);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public UploadJobVo createJob(UploadJobCreateRequest request, String userId) {
        ProductUploadJobEntity entity = new ProductUploadJobEntity();
        entity.setStoreId(UUID.fromString(request.getStoreId()));
        entity.setProductId(UUID.fromString(request.getProductId()));
        entity.setMarketplaceId(UUID.fromString(request.getMarketplaceId()));
        entity.setUploadMethod(normalizeUploadMethod(request.getUploadMethod()));
        entity.setStatus("draft");
        entity.setCreatedBy(userId != null ? UUID.fromString(userId) : null);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        uploadJobMapper.insert(entity);
        log.info("Upload job created: id={}, productId={}", entity.getId(), request.getProductId());
        return toVo(entity);
    }

    @Override
    @Transactional
    public UploadJobVo validateJob(String id) {
        ProductUploadJobEntity job = uploadJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("UPLOAD_JOB_NOT_FOUND", "Upload job not found: " + id);
        }

        List<String> errors = new ArrayList<>();

        // Get product
        ProductEntity product = productMapper.selectById(job.getProductId());
        if (product == null) {
            errors.add("Product not found");
        } else {
            // SKU required
            if (product.getSku() == null || product.getSku().isBlank()) {
                errors.add("SKU is required");
            }
            // Brand required
            if (product.getBrand() == null || product.getBrand().isBlank()) {
                errors.add("Brand is required");
            }
            // Price required
            if (product.getPrice() == null || product.getPrice().signum() <= 0) {
                errors.add("Price is required and must be positive");
            }
            // Inventory required
            if (product.getInventory() == null || product.getInventory() < 0) {
                errors.add("Inventory is required and must be non-negative");
            }
        }

        // Get listing content
        LambdaQueryWrapper<ListingContentEntity> listingWrapper = new LambdaQueryWrapper<>();
        listingWrapper.eq(ListingContentEntity::getProductId, job.getProductId())
                      .orderByDesc(ListingContentEntity::getUpdatedAt)
                      .last("LIMIT 1");
        ListingContentEntity listing = listingContentMapper.selectOne(listingWrapper);

        if (listing == null) {
            errors.add("Listing content not found. Generate a listing first.");
        } else {
            // Title required
            if (listing.getTitle() == null || listing.getTitle().isBlank()) {
                errors.add("Listing title is required");
            }

            // Bullet points >= 1
            if (listing.getBulletPoints() == null || "[]".equals(listing.getBulletPoints())) {
                errors.add("At least 1 bullet point is required");
            } else {
                try {
                    List<?> bullets = objectMapper.readValue(listing.getBulletPoints(), List.class);
                    if (bullets.isEmpty()) {
                        errors.add("At least 1 bullet point is required");
                    }
                } catch (JsonProcessingException e) {
                    errors.add("Invalid bullet points format");
                }
            }

            // Description required
            if (listing.getDescription() == null || listing.getDescription().isBlank()) {
                errors.add("Description is required");
            }

            // Amazon flat-file exports use the backend search-term 250-byte limit.
            if (isAmazonListingMethod(job.getUploadMethod()) && listing.getBackendSearchTerms() != null &&
                    listing.getBackendSearchTerms().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 250) {
                errors.add("Backend search terms exceed 250 bytes (" +
                        listing.getBackendSearchTerms().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " bytes)");
            }

            // No competitor brand names in listing (check title + bullets + description)
            if (product != null && product.getBrand() != null) {
                String allText = (listing.getTitle() != null ? listing.getTitle() : "") + " " +
                        (listing.getDescription() != null ? listing.getDescription() : "") + " " +
                        (listing.getBulletPoints() != null ? listing.getBulletPoints() : "");
                String lower = allText.toLowerCase();
                // Common competitor brand patterns - this is a simplified check
                List<String> competitorBrands = List.of("apple", "samsung", "sony", "bose", "lg", "nike", "adidas");
                String myBrand = product.getBrand().toLowerCase();
                for (String brand : competitorBrands) {
                    if (!brand.equals(myBrand) && lower.contains(brand)) {
                        errors.add("Listing contains competitor brand name: " + brand);
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            job.setStatus("failed");
            job.setErrorMessage(String.join("; ", errors));
        } else {
            job.setStatus("ready");
            job.setErrorMessage(null);
        }
        job.setUpdatedAt(LocalDateTime.now());
        uploadJobMapper.updateById(job);

        log.info("Upload job validated: id={}, status={}, errors={}", id, job.getStatus(), errors.size());
        return toVo(job);
    }

    @Override
    @Transactional
    public UploadJobVo approveJob(String id, String userId) {
        ProductUploadJobEntity job = uploadJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("UPLOAD_JOB_NOT_FOUND", "Upload job not found: " + id);
        }
        if (!"ready".equals(job.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "Job must be in 'ready' status to approve. Current: " + job.getStatus());
        }
        job.setStatus("approved");
        job.setUpdatedAt(LocalDateTime.now());
        uploadJobMapper.updateById(job);
        log.info("Upload job approved: id={}", id);
        return toVo(job);
    }

    @Override
    @Transactional
    public UploadJobVo cancelJob(String id, String userId) {
        ProductUploadJobEntity job = uploadJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("UPLOAD_JOB_NOT_FOUND", "Upload job not found: " + id);
        }
        if ("submitted".equals(job.getStatus()) || "success".equals(job.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "Cannot cancel a job that is already submitted or completed.");
        }
        job.setStatus("cancelled");
        job.setUpdatedAt(LocalDateTime.now());
        uploadJobMapper.updateById(job);
        log.info("Upload job cancelled: id={}", id);
        return toVo(job);
    }

    @Override
    public String exportJob(String id, String format) {
        ProductUploadJobEntity job = uploadJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("UPLOAD_JOB_NOT_FOUND", "Upload job not found: " + id);
        }
        if (!"approved".equals(job.getStatus()) && !"exported".equals(job.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "Job must be approved before export. Current: " + job.getStatus());
        }

        ProductEntity product = productMapper.selectById(job.getProductId());
        LambdaQueryWrapper<ListingContentEntity> listingWrapper = new LambdaQueryWrapper<>();
        listingWrapper.eq(ListingContentEntity::getProductId, job.getProductId())
                      .orderByDesc(ListingContentEntity::getUpdatedAt)
                      .last("LIMIT 1");
        ListingContentEntity listing = listingContentMapper.selectOne(listingWrapper);

        Map<String, Object> exportData = new LinkedHashMap<>();
        exportData.put("upload_job_id", id);
        exportData.put("upload_method", job.getUploadMethod());
        exportData.put("publish_mode", "export_only");
        if (product != null) {
            exportData.put("sku", product.getSku());
            exportData.put("brand", product.getBrand());
            exportData.put("price", product.getPrice());
            exportData.put("inventory", product.getInventory());
        }
        if (listing != null) {
            exportData.put("title", listing.getTitle());
            exportData.put("bullet_points", listing.getBulletPoints());
            exportData.put("description", listing.getDescription());
            exportData.put("backend_search_terms", listing.getBackendSearchTerms());
        }

        job.setStatus("exported");
        job.setUpdatedAt(LocalDateTime.now());
        uploadJobMapper.updateById(job);

        try {
            if ("flat_file".equalsIgnoreCase(format) || "tsv".equalsIgnoreCase(format)) {
                // Generate TSV format
                StringBuilder sb = new StringBuilder();
                sb.append(String.join("\t", exportData.keySet())).append("\n");
                sb.append(exportData.values().stream()
                        .map(v -> v != null ? v.toString().replace("\t", " ").replace("\n", " ") : "")
                        .collect(Collectors.joining("\t")));
                return sb.toString();
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(exportData);
        } catch (JsonProcessingException e) {
            throw new BusinessException("EXPORT_FAILED", "Failed to serialize export data");
        }
    }

    @Override
    @Transactional
    public UploadJobVo publishJob(String id, String userId) {
        ProductUploadJobEntity job = uploadJobMapper.selectById(UUID.fromString(id));
        if (job == null) {
            throw new BusinessException("UPLOAD_JOB_NOT_FOUND", "Upload job not found: " + id);
        }
        if (!IndependentSiteProductPublishService.isDirectPublishMethod(job.getUploadMethod())) {
            throw new BusinessException("NOT_DIRECT_PUBLISH",
                    "Upload method does not support direct publish: " + job.getUploadMethod()
                            + ". Use export instead.");
        }
        // Only an approved (or a previously-failed retry) job may be published.
        if (!"approved".equals(job.getStatus()) && !"failed".equals(job.getStatus())) {
            throw new BusinessException("INVALID_STATUS",
                    "Job must be approved before publish. Current: " + job.getStatus());
        }

        ProductEntity product = productMapper.selectById(job.getProductId());
        LambdaQueryWrapper<ListingContentEntity> listingWrapper = new LambdaQueryWrapper<>();
        listingWrapper.eq(ListingContentEntity::getProductId, job.getProductId())
                      .orderByDesc(ListingContentEntity::getUpdatedAt)
                      .last("LIMIT 1");
        ListingContentEntity listing = listingContentMapper.selectOne(listingWrapper);

        ProductPublishOutcome outcome;
        try {
            outcome = publishService.publish(job, product, listing);
        } catch (Exception e) {
            // Transport/credential failure: leave the job failed with a readable reason. Never faked.
            String reason = rootMessage(e);
            job.setStatus("failed");
            job.setErrorMessage("直发失败（网络/凭据错误）：" + reason);
            job.setResponse(writeResponse(Map.of(
                    "publish_mode", "direct",
                    "upload_method", job.getUploadMethod(),
                    "error", reason)));
            job.setUpdatedAt(LocalDateTime.now());
            uploadJobMapper.updateById(job);
            log.warn("Upload job direct-publish transport/credential error: id={}, reason={}", id, reason);
            return toVo(job);
        }

        if (outcome.published()) {
            job.setStatus("published");
            job.setErrorMessage(null);
            job.setResponse(writeResponse(Map.of(
                    "publish_mode", outcome.publishMode(),
                    "upload_method", job.getUploadMethod(),
                    "platform_product_id", outcome.platformProductId() != null ? outcome.platformProductId() : "",
                    "message", outcome.reason() != null ? outcome.reason() : "")));
            job.setUpdatedAt(LocalDateTime.now());
            uploadJobMapper.updateById(job);
            log.info("Upload job published directly: id={}, platformProductId={}",
                    id, outcome.platformProductId());
        } else {
            // No connection / missing creds / platform rejection → failed, never exported, never faked.
            job.setStatus("failed");
            job.setErrorMessage(outcome.reason());
            job.setResponse(writeResponse(Map.of(
                    "publish_mode", "direct",
                    "upload_method", job.getUploadMethod(),
                    "error_code", outcome.errorCode() != null ? outcome.errorCode() : "",
                    "error", outcome.reason() != null ? outcome.reason() : "")));
            job.setUpdatedAt(LocalDateTime.now());
            uploadJobMapper.updateById(job);
            log.info("Upload job direct-publish failed: id={}, errorCode={}, reason={}",
                    id, outcome.errorCode(), outcome.reason());
        }
        return toVo(job);
    }

    private String writeResponse(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }

    private UploadJobVo toVo(ProductUploadJobEntity entity) {
        return UploadJobVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .productId(entity.getProductId() != null ? entity.getProductId().toString() : null)
                .marketplaceId(entity.getMarketplaceId() != null ? entity.getMarketplaceId().toString() : null)
                .uploadMethod(entity.getUploadMethod())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private String normalizeUploadMethod(String uploadMethod) {
        String normalized = uploadMethod == null || uploadMethod.isBlank()
                ? "flat_file"
                : uploadMethod.trim().toLowerCase(Locale.ROOT);
        if (EXPORT_ONLY_UPLOAD_METHODS.contains(normalized)) {
            return normalized;
        }
        if (DIRECT_PUBLISH_UPLOAD_METHODS.contains(normalized)) {
            // Real direct-publish path (Shopify / WooCommerce). Kept as-is; no downgrade.
            return normalized;
        }
        if (DIRECT_CONNECTORS_NOT_IMPLEMENTED.contains(normalized)) {
            log.warn("Direct product publish connector is not implemented for uploadMethod={}, falling back to manual_export", normalized);
            return "manual_export";
        }
        throw new BusinessException("UNSUPPORTED_UPLOAD_METHOD", "Unsupported upload method: " + uploadMethod);
    }

    private boolean isAmazonListingMethod(String uploadMethod) {
        return "amazon_flat_file".equalsIgnoreCase(uploadMethod) || "flat_file".equalsIgnoreCase(uploadMethod);
    }
}
