package com.adpilot.modules.finance.service;

import com.adpilot.modules.finance.vo.VatInclusiveAmount;
import com.adpilot.modules.store.entity.MarketplaceEntity;

import java.math.BigDecimal;

/**
 * Includes the VAT component in a Store's financial calculations (Req 9.2.3).
 *
 * <p>Whether VAT applies and at what rate is determined by the Store's
 * marketplace: when the marketplace is subject to VAT the computed VAT
 * component is included in the resulting figure; otherwise no VAT is applied.</p>
 */
public interface VatService {

    /**
     * Includes the VAT component for {@code netAmount} based on whether
     * {@code marketplace} is subject to VAT (Req 9.2.3).
     *
     * <p>When the marketplace is subject to VAT, the result carries the VAT
     * component and the VAT-inclusive gross amount. When it is not, the VAT
     * component is zero and the gross equals the net amount.</p>
     *
     * @param netAmount   the amount before VAT
     * @param marketplace the Store's marketplace, carrying VAT applicability and rate
     * @return the net amount, the included VAT component, and the gross amount
     */
    VatInclusiveAmount includeVat(BigDecimal netAmount, MarketplaceEntity marketplace);
}
