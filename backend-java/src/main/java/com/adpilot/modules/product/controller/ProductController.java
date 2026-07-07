package com.adpilot.modules.product.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.product.dto.ProductDto;
import com.adpilot.modules.product.service.ProductService;
import com.adpilot.modules.product.vo.ProductVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * GET /api/products - List products with pagination.
     */
    @GetMapping
    public ApiResponse<PageResponse<ProductVo>> listProducts(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ProductVo> result = productService.listProducts(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/products/{id} - Get product by ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<ProductVo> getProduct(@PathVariable String id) {
        ProductVo product = productService.getProductById(id);
        return ApiResponse.ok(product);
    }

    /**
     * POST /api/products - Create a new product.
     */
    @PostMapping
    @RequirePermission("product:create")
    public ApiResponse<ProductVo> createProduct(@Valid @RequestBody ProductDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        ProductVo product = productService.createProduct(dto, userId);
        return ApiResponse.ok(product);
    }

    /**
     * PUT /api/products/{id} - Update an existing product.
     */
    @PutMapping("/{id}")
    @RequirePermission("product:update")
    public ApiResponse<ProductVo> updateProduct(@PathVariable String id, @Valid @RequestBody ProductDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        ProductVo product = productService.updateProduct(id, dto, userId);
        return ApiResponse.ok(product);
    }

    /**
     * DELETE /api/products/{id} - Delete a product.
     */
    @DeleteMapping("/{id}")
    @RequirePermission("product:delete")
    public ApiResponse<Void> deleteProduct(@PathVariable String id) {
        productService.deleteProduct(id);
        return ApiResponse.ok(null);
    }
}
