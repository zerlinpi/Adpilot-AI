package com.adpilot.modules.store.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StoreVo {

    private String id;
    private String name;
    private String marketplaceId;
    private String marketplaceName;
    private String marketplaceCode;
    private String marketplaceCurrency;
    private String marketplaceTimezone;
    private String sellerId;
    private String status;
    /** Normalized platform family: amazon | shopify | woocommerce | tiktok. */
    private String platform;
    /** Store platform group: marketplace | independent_site | unknown. */
    private String platformGroup;
    /** Default ad platform for this store: amazon_ads | google_ads | tiktok_ads | none. */
    private String adPlatform;
    private Integer productCount;
    private String createdAt;
    /** Optional group label used to organize stores in the switcher (Req 5.2.4). */
    private String storeGroup;
}
