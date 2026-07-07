package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Creative-asset row for the Creative Asset Library page (Req 29.1). Carries the
 * asset's type (so the page can render the category), media kind, stored URL,
 * optional ASIN, tags, and the resolved creator display name (创建人).
 */
@Data
@Builder
public class CreativeAssetVo {

    private String id;
    private String storeId;

    /** Asset name (素材名称). */
    private String name;

    /** Creative category: {@code lifestyle|scene|hd_group|marketing}. */
    private String assetType;

    /** Media kind: {@code image} or {@code video}. */
    private String mediaKind;

    /** Stored location (download/preview URL). */
    private String storageUrl;

    /** Optional associated ASIN. */
    private String asin;

    /** Free-form tags (标签). */
    private List<String> tags;

    /** Resolved creator display name (创建人); falls back to the id when unknown. */
    private String creator;

    private String createdAt;
}
