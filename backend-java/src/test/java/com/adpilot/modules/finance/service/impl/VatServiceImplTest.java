package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.vo.VatInclusiveAmount;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link VatServiceImpl} covering VAT inclusion in a Store's
 * financial calculations (Req 9.2.3): the VAT component IS included when the
 * Store's marketplace is subject to VAT and is NOT applied when it is not.
 */
class VatServiceImplTest {

    private final VatServiceImpl vatService = new VatServiceImpl();

    private MarketplaceEntity marketplace(Boolean vatApplicable, String vatRate) {
        return MarketplaceEntity.builder()
                .code("XX")
                .name("Test Marketplace")
                .currency("EUR")
                .vatApplicable(vatApplicable)
                .vatRate(vatRate == null ? null : new BigDecimal(vatRate))
                .build();
    }

    @Test
    void includesVatComponentWhenMarketplaceIsSubjectToVat() {
        // Req 9.2.3: VAT-subject marketplace -> VAT component included in the figure.
        MarketplaceEntity de = marketplace(true, "0.1900");

        VatInclusiveAmount result = vatService.includeVat(new BigDecimal("100.00"), de);

        assertThat(result.vatApplicable()).isTrue();
        assertThat(result.net()).isEqualByComparingTo("100.00");
        assertThat(result.vatComponent()).isEqualByComparingTo("19.0000");
        assertThat(result.gross()).isEqualByComparingTo("119.0000");
    }

    @Test
    void doesNotApplyVatWhenMarketplaceIsNotSubjectToVat() {
        // Req 9.2.3 (negative): non-VAT marketplace -> no VAT component, gross == net.
        MarketplaceEntity us = marketplace(false, null);

        VatInclusiveAmount result = vatService.includeVat(new BigDecimal("100.00"), us);

        assertThat(result.vatApplicable()).isFalse();
        assertThat(result.net()).isEqualByComparingTo("100.00");
        assertThat(result.vatComponent()).isEqualByComparingTo("0");
        assertThat(result.gross()).isEqualByComparingTo("100.00");
    }

    @Test
    void doesNotApplyVatWhenRateIsMissingOrNonPositive() {
        // Flag set but no/zero rate must not fabricate a VAT component.
        VatInclusiveAmount noRate = vatService.includeVat(new BigDecimal("50.00"), marketplace(true, null));
        assertThat(noRate.vatApplicable()).isFalse();
        assertThat(noRate.gross()).isEqualByComparingTo("50.00");

        VatInclusiveAmount zeroRate = vatService.includeVat(new BigDecimal("50.00"), marketplace(true, "0.0000"));
        assertThat(zeroRate.vatApplicable()).isFalse();
        assertThat(zeroRate.gross()).isEqualByComparingTo("50.00");
    }

    @Test
    void treatsNullMarketplaceAsNotSubjectToVat() {
        VatInclusiveAmount result = vatService.includeVat(new BigDecimal("75.00"), null);

        assertThat(result.vatApplicable()).isFalse();
        assertThat(result.gross()).isEqualByComparingTo("75.00");
    }

    @Test
    void rejectsMissingNetAmount() {
        assertThatThrownBy(() -> vatService.includeVat(null, marketplace(true, "0.2000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
