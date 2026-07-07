package com.adpilot.modules.product.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.product.dto.ProductDto;
import com.adpilot.modules.product.vo.ProductVo;

public interface ProductService {

    /**
     * List products with pagination.
     */
    PageResponse<ProductVo> listProducts(int page, int pageSize);

    /**
     * List products scoped to a specific store (in addition to the caller's
     * data scope), with pagination. When {@code storeId} is null/blank this
     * behaves like {@link #listProducts(int, int)}.
     */
    PageResponse<ProductVo> listProducts(String storeId, int page, int pageSize);

    /**
     * Get product by ID.
     */
    ProductVo getProductById(String id);

    /**
     * Create a new product.
     */
    ProductVo createProduct(ProductDto dto, String userId);

    /**
     * Update an existing product.
     */
    ProductVo updateProduct(String id, ProductDto dto, String userId);

    /**
     * Delete a product by ID.
     */
    void deleteProduct(String id);
}
