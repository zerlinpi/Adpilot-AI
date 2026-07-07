package com.adpilot.modules.settlement.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.settlement.dto.SettlementDto;
import com.adpilot.modules.settlement.vo.SettlementVo;

public interface SettlementService {

    /**
     * List settlements with pagination.
     */
    PageResponse<SettlementVo> listSettlements(String storeId, int page, int pageSize);

    /**
     * Get settlement by ID.
     */
    SettlementVo getSettlementById(String id);

    /**
     * Import a settlement record.
     */
    SettlementVo importSettlement(SettlementDto dto);
}
