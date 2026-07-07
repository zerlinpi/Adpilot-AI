package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.vo.CampaignVo;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Amazon-synced-list gating in {@link CampaignConverter} (Req 12.7, 12.8; Property 29):
 * a Campaign appears in the Amazon-synced list iff it has a non-empty {@code amazon_campaign_id} AND
 * its creation Operation reached {@code effective}; otherwise it carries a local-source badge.
 */
class CampaignConverterSyncedListTest {

    private static CampaignEntity.CampaignEntityBuilder base() {
        return CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("Campaign");
    }

    // ---- syncedToAmazon predicate ---------------------------------------------------------------

    @Test
    void localCampaignWithAmazonIdAndEffectiveCreationIsSynced() {
        CampaignEntity e = base().origin("local").amazonCampaignId("AMZ-123").build();
        assertThat(CampaignConverter.syncedToAmazon(e, true)).isTrue();
    }

    @Test
    void localCampaignWithAmazonIdButCreationNotEffectiveIsNotSynced() {
        CampaignEntity e = base().origin("local").amazonCampaignId("AMZ-123").build();
        assertThat(CampaignConverter.syncedToAmazon(e, false)).isFalse();
    }

    @Test
    void localCampaignWithoutAmazonIdIsNeverSynced() {
        CampaignEntity e = base().origin("local").amazonCampaignId(null).build();
        assertThat(CampaignConverter.syncedToAmazon(e, true)).isFalse();
        assertThat(CampaignConverter.syncedToAmazon(e, false)).isFalse();
    }

    @Test
    void blankAmazonIdIsTreatedAsAbsent() {
        CampaignEntity e = base().origin("local").amazonCampaignId("   ").build();
        assertThat(CampaignConverter.syncedToAmazon(e, true)).isFalse();
    }

    @Test
    void amazonImportCampaignWithAmazonIdIsSyncedWithoutCreationOperation() {
        // An ingested Campaign already exists on Amazon, so an Amazon id alone qualifies it.
        CampaignEntity e = base().origin("amazon_import").amazonCampaignId("AMZ-999").build();
        assertThat(CampaignConverter.syncedToAmazon(e, false)).isTrue();
    }

    @Test
    void amazonImportCampaignWithoutAmazonIdIsNotSynced() {
        CampaignEntity e = base().origin("amazon_import").amazonCampaignId(null).build();
        assertThat(CampaignConverter.syncedToAmazon(e, false)).isFalse();
    }

    @Test
    void nullEntityIsNotSynced() {
        assertThat(CampaignConverter.syncedToAmazon(null, true)).isFalse();
    }

    // ---- VO projection: synced flag + local-source badge ----------------------------------------

    @Test
    void voExposesSyncedFlagAndLocalSourceBadgeAsExactNegation() {
        CampaignEntity synced = base().origin("local").amazonCampaignId("AMZ-1").build();
        CampaignVo syncedVo = CampaignConverter.toVo(synced, true);
        assertThat(syncedVo.isSyncedToAmazon()).isTrue();
        assertThat(syncedVo.isLocalSource()).isFalse();
        assertThat(syncedVo.getAmazonCampaignId()).isEqualTo("AMZ-1");
        assertThat(syncedVo.getOrigin()).isEqualTo("local");

        CampaignEntity draft = base().origin("local").amazonCampaignId(null).build();
        CampaignVo draftVo = CampaignConverter.toVo(draft, false);
        assertThat(draftVo.isSyncedToAmazon()).isFalse();
        assertThat(draftVo.isLocalSource()).isTrue();
    }

    @Test
    void singleArgConversionDefaultsLocalCampaignToLocalDraft() {
        // Without a resolved creation Operation, a local Campaign with an Amazon id stays a draft.
        CampaignEntity e = base().origin("local").amazonCampaignId("AMZ-2").build();
        CampaignVo vo = CampaignConverter.toVo(e);
        assertThat(vo.isSyncedToAmazon()).isFalse();
        assertThat(vo.isLocalSource()).isTrue();
    }

    @Test
    void singleArgConversionStillRecognizesAmazonImportAsSynced() {
        CampaignEntity e = base().origin("amazon_import").amazonCampaignId("AMZ-3").build();
        CampaignVo vo = CampaignConverter.toVo(e);
        assertThat(vo.isSyncedToAmazon()).isTrue();
        assertThat(vo.isLocalSource()).isFalse();
    }
}
