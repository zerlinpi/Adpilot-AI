package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.service.VatService;
import com.adpilot.modules.finance.vo.VatInclusiveAmount;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Default {@link VatService}. Includes the VAT component in a Store's financial
 * figure when its marketplace is subject to VAT (Req 9.2.3), and leaves the
 * figure untouched when it is not.
 */
@Service
public class VatServiceImpl implements VatService {

    /** Scale used for monetary amounts; matches the 4-dp monetary columns. */
    private static final int MONEY_SCALE = 4;

    @Override
    public VatInclusiveAmount includeVat(BigDecimal netAmount, MarketplaceEntity marketplace) {
        if (netAmount == null) {
            throw new IllegalArgumentException("netAmount is required");
        }

        BigDecimal net = netAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // Req 9.2.3: only marketplaces subject to VAT with a positive rate
        // contribute a VAT component; otherwise the figure is left untouched.
        if (!isSubjectToVat(marketplace)) {
            return new VatInclusiveAmount(net, BigDecimal.ZERO.setScale(MONEY_SCALE), net, false);
        }

        BigDecimal vatComponent = net.multiply(marketplace.getVatRate())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal gross = net.add(vatComponent);

        return new VatInclusiveAmount(net, vatComponent, gross, true);
    }

    private boolean isSubjectToVat(MarketplaceEntity marketplace) {
        return marketplace != null
                && Boolean.TRUE.equals(marketplace.getVatApplicable())
                && marketplace.getVatRate() != null
                && marketplace.getVatRate().compareTo(BigDecimal.ZERO) > 0;
    }
}
