package com.adpilot.modules.profit.service;

import com.adpilot.modules.profit.vo.ProfitAttributionVo;
import com.adpilot.modules.profit.vo.ProfitDashboardVo;
import com.adpilot.modules.profit.vo.ProductProfitVo;

import java.util.List;

public interface ProfitService {

    ProfitDashboardVo getProfitDashboard(String storeId, String startDate, String endDate);

    List<ProductProfitVo> getProductProfits(String storeId, String startDate, String endDate);

    List<ProfitAttributionVo> getProfitAttribution(String storeId);
}
