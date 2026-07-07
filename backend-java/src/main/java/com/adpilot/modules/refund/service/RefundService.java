package com.adpilot.modules.refund.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.refund.dto.RefundDto;
import com.adpilot.modules.refund.vo.RefundVo;

import java.util.List;

public interface RefundService {

    /**
     * List refunds with pagination.
     */
    PageResponse<RefundVo> listRefunds(String storeId, int page, int pageSize);

    /**
     * Get refund by ID.
     */
    RefundVo getRefundById(String id);

    /**
     * Import refunds in batch.
     */
    List<RefundVo> importRefunds(List<RefundDto> dtos, String userId);
}
