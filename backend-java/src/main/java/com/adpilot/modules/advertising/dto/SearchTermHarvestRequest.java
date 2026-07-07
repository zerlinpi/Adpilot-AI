package com.adpilot.modules.advertising.dto;

import lombok.Data;

/**
 * Request body for harvesting a Search_Term (Req 13).
 *
 * <p>The {@code harvestAction} selects which object harvesting produces. The
 * target campaign/ad group and match type are OPTIONAL overrides: when omitted,
 * the target is derived from the Search_Term's associated campaign and ad group
 * (Req 13.1); when supplied, the override target is used instead and validated
 * against the caller's effective data scope (Req 13.2).
 */
@Data
public class SearchTermHarvestRequest {

    /**
     * The chosen Harvest_Action: one of {@code add_exact}, {@code add_phrase},
     * {@code add_negative}, or {@code watchlist}. An unrecognised value is
     * rejected (Req 13.7). Validated in the service so the error can name the
     * invalid action rather than emitting a generic bean-validation message.
     */
    private String harvestAction;

    /** Optional override target campaign (Req 13.2); derived from the term when null. */
    private String targetCampaignId;

    /** Optional override target ad group (Req 13.2); derived from the term when null. */
    private String targetAdGroupId;

    /** Optional override match type for keyword actions. */
    private String matchType;

    /** Optional bid for a created keyword; falls back to the ad group default bid. */
    private String bid;
}
