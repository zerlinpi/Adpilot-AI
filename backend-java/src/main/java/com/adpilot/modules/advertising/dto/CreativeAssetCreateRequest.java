package com.adpilot.modules.advertising.dto;

import lombok.Data;

import java.util.List;

/**
 * Form fields accompanying the multipart {@code POST /api/creative-assets}
 * upload (Req 29.2). The binary content is carried separately as the
 * {@code file} part; these fields describe the asset. {@code storeId} and
 * {@code name} are required (validated in the service); {@code assetType}
 * defaults to {@code lifestyle} and {@code mediaKind} is inferred from the
 * uploaded file when not supplied.
 */
@Data
public class CreativeAssetCreateRequest {

    private String storeId;
    private String name;

    /** Creative category: {@code lifestyle|scene|hd_group|marketing}. */
    private String assetType;

    /** Media kind: {@code image} or {@code video}; inferred from the file when blank. */
    private String mediaKind;

    /** Optional associated ASIN. */
    private String asin;

    /** Optional free-form tags (标签). */
    private List<String> tags;
}
