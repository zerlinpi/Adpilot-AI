package com.adpilot.modules.returnorder.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.returnorder.dto.ReturnDto;
import com.adpilot.modules.returnorder.vo.ReturnVo;

import java.util.List;

public interface ReturnService {

    /**
     * List returns with pagination, optionally filtered by store.
     */
    PageResponse<ReturnVo> listReturns(String storeId, int page, int pageSize);

    /**
     * Get return by ID.
     */
    ReturnVo getReturnById(String id);

    /**
     * Import returns in batch.
     */
    List<ReturnVo> importReturns(List<ReturnDto> dtos, String userId);
}
