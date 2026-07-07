package com.adpilot.modules.product.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.product.dto.ProductDto;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.product.service.ProductService;
import com.adpilot.modules.product.vo.ProductVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Store + owner scope target for products (Req 7.1.5). */
    private static final ScopeTarget PRODUCT_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<ProductVo> listProducts(int page, int pageSize) {
        return listProducts(null, page, pageSize);
    }

    @Override
    public PageResponse<ProductVo> listProducts(String storeId, int page, int pageSize) {
        Page<ProductEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ProductEntity> wrapper = new QueryWrapper<>();
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, PRODUCT_SCOPE, user);
        }
        // Scope to the active store when provided (Req: store-scoped product list).
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        wrapper.orderByDesc("created_at");

        Page<ProductEntity> result = productMapper.selectPage(pageParam, wrapper);
        List<ProductVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public ProductVo getProductById(String id) {
        ProductEntity entity = productMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public ProductVo createProduct(ProductDto dto, String userId) {
        ProductEntity entity = ProductEntity.builder()
                .storeId(UUID.fromString(dto.getStoreId()))
                .sku(dto.getSku())
                .asin(dto.getAsin())
                .name(dto.getName())
                .imageUrl(dto.getImageUrl())
                .price(dto.getPrice() != null ? dto.getPrice() : BigDecimal.ZERO)
                .cost(dto.getCost() != null ? dto.getCost() : BigDecimal.ZERO)
                .grossMargin(dto.getGrossMargin() != null ? dto.getGrossMargin() : BigDecimal.ZERO)
                .inventory(dto.getInventory() != null ? dto.getInventory() : 0)
                .targetAcos(dto.getTargetAcos() != null ? dto.getTargetAcos() : BigDecimal.ZERO)
                .breakEvenAcos(dto.getBreakEvenAcos() != null ? dto.getBreakEvenAcos() : BigDecimal.ZERO)
                .category(dto.getCategory())
                .brand(dto.getBrand())
                .status("active")
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .updatedBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        productMapper.insert(entity);
        log.info("Product created: id={}, sku={}", entity.getId(), entity.getSku());
        return toVo(entity);
    }

    @Override
    @Transactional
    public ProductVo updateProduct(String id, ProductDto dto, String userId) {
        ProductEntity entity = productMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + id);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        if (dto.getName() != null) {
            entity.setName(dto.getName());
        }
        if (dto.getSku() != null) {
            entity.setSku(dto.getSku());
        }
        if (dto.getAsin() != null) {
            entity.setAsin(dto.getAsin());
        }
        if (dto.getImageUrl() != null) {
            entity.setImageUrl(dto.getImageUrl());
        }
        if (dto.getPrice() != null) {
            entity.setPrice(dto.getPrice());
        }
        if (dto.getCost() != null) {
            entity.setCost(dto.getCost());
        }
        if (dto.getGrossMargin() != null) {
            entity.setGrossMargin(dto.getGrossMargin());
        }
        if (dto.getInventory() != null) {
            entity.setInventory(dto.getInventory());
        }
        if (dto.getTargetAcos() != null) {
            entity.setTargetAcos(dto.getTargetAcos());
        }
        if (dto.getBreakEvenAcos() != null) {
            entity.setBreakEvenAcos(dto.getBreakEvenAcos());
        }
        if (dto.getCategory() != null) {
            entity.setCategory(dto.getCategory());
        }
        if (dto.getBrand() != null) {
            entity.setBrand(dto.getBrand());
        }
        if (dto.getStoreId() != null) {
            entity.setStoreId(UUID.fromString(dto.getStoreId()));
        }

        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        entity.setUpdatedAt(LocalDateTime.now());

        productMapper.updateById(entity);
        log.info("Product updated: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void deleteProduct(String id) {
        ProductEntity entity = productMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }
        productMapper.deleteById(UUID.fromString(id));
        log.info("Product deleted: id={}", id);
    }

    private ProductVo toVo(ProductEntity entity) {
        return ProductVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .sku(entity.getSku())
                .asin(entity.getAsin())
                .name(entity.getName())
                .imageUrl(entity.getImageUrl())
                .price(entity.getPrice() != null ? entity.getPrice().doubleValue() : 0.0)
                .cost(entity.getCost() != null ? entity.getCost().doubleValue() : 0.0)
                .grossMargin(entity.getGrossMargin() != null ? entity.getGrossMargin().doubleValue() : 0.0)
                .inventory(entity.getInventory() != null ? entity.getInventory() : 0)
                .targetAcos(entity.getTargetAcos() != null ? entity.getTargetAcos().doubleValue() : 0.0)
                .breakEvenAcos(entity.getBreakEvenAcos() != null ? entity.getBreakEvenAcos().doubleValue() : 0.0)
                .category(entity.getCategory())
                .brand(entity.getBrand())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
