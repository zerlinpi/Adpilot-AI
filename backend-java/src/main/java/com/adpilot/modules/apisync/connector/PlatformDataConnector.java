package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.DiscoveredStore;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.PageCursor;

import java.time.Instant;
import java.util.List;

/**
 * Service Provider Interface (SPI) for <em>pulling</em> data from an external
 * platform. One implementation exists per platform (WooCommerce and Shopify
 * first; Amazon SP-API/Ads in P2). The {@code SyncJobRunner} drives the same
 * orchestration across all implementations: connector &rarr; mapper &rarr;
 * validator &rarr; upsert &rarr; watermark.
 *
 * <p>This complements {@link PlatformConnector}, which already performs real
 * credential validation against every supported platform. Where today the
 * connector only <em>tests</em> connectivity, this SPI adds the contract for
 * <em>retrieving</em> orders, products, and (for Amazon) inventory and ad
 * reports. Implementations reuse {@link PlatformConnector#test} for credential
 * validation so the existing behavior is preserved.</p>
 *
 * <h2>Credentials &amp; security</h2>
 * <p>Credentials are supplied via {@link ConnectionContext#credentials()},
 * decrypted on demand via {@code CryptoUtil} by the caller that builds the
 * context (the sync job runner). Implementations MUST NOT log credential
 * values; {@link ConnectionContext#toString()} already redacts them.</p>
 *
 * <h2>Paging &amp; incremental pulls</h2>
 * <p>Pull methods accept a {@code since} instant and a {@link PageCursor}.
 * A {@code null} {@code since} (or an explicit full resync) means a full
 * retrieval; otherwise only records created or changed after {@code since}
 * are returned (Req 1.1.6, 1.1.7). Callers iterate by passing the
 * {@link ExternalPage#next()} cursor back until
 * {@link ExternalPage#hasMore()} is {@code false}.</p>
 */
public interface PlatformDataConnector {

    /**
     * The platform key this connector serves, matching the values in
     * {@link PlatformConnector#SUPPORTED} (e.g. {@code "woocommerce"},
     * {@code "shopify"}, {@code "amazon_sp_api"}).
     */
    String platform();

    /**
     * Whether this connector validates its own credentials during the pull
     * (acquiring/refreshing access tokens and signalling re-authorization via
     * {@link ReauthRequiredException}). When {@code true}, the sync job runner
     * skips its generic credential pre-check so that an expired/invalid token
     * surfaces as a re-auth signal rather than a generic credential rejection
     * (Req 8.1.5). Defaults to {@code false} for connectors (WooCommerce,
     * Shopify) whose credentials the runner validates up front.
     */
    default boolean selfValidatesCredentials() {
        return false;
    }

    /**
     * Pull a page of orders for the connection. With a {@code null}
     * {@code since}, performs a full retrieval; otherwise retrieves only orders
     * created or changed strictly after {@code since}.
     *
     * @param ctx    decrypted connection context (never logged)
     * @param since  lower bound for incremental retrieval; {@code null} for a
     *               full pull
     * @param cursor paging cursor; {@link PageCursor#start()} for the first page
     * @return a page of normalized order records plus the next cursor
     */
    ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor);

    /**
     * Pull a page of products for the connection. With a {@code null}
     * {@code since}, performs a full retrieval; otherwise retrieves only
     * products created or changed strictly after {@code since}.
     *
     * @param ctx    decrypted connection context (never logged)
     * @param since  lower bound for incremental retrieval; {@code null} for a
     *               full pull
     * @param cursor paging cursor; {@link PageCursor#start()} for the first page
     * @return a page of normalized product records plus the next cursor
     */
    ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor);

    /**
     * Pull a page of inventory records. Optional capability; supported by
     * Amazon connectors in P2 (Req 8.1.1). Platforms that do not expose a
     * distinct inventory feed leave this unimplemented.
     *
     * @throws UnsupportedOperationException if the platform does not support
     *                                       inventory pulls
     */
    default ExternalPage pullInventory(ConnectionContext ctx, Instant since, PageCursor cursor) {
        throw new UnsupportedOperationException(
                "Platform '" + platform() + "' does not support inventory pulls");
    }

    /**
     * Pull a page of advertising report records. Optional capability; supported
     * by Amazon Ads connectors in P2 (Req 8.1.1, 8.1.3).
     *
     * @throws UnsupportedOperationException if the platform does not support
     *                                       advertising-report pulls
     */
    default ExternalPage pullAdReports(ConnectionContext ctx, Instant since, PageCursor cursor) {
        throw new UnsupportedOperationException(
                "Platform '" + platform() + "' does not support advertising-report pulls");
    }

    /**
     * Discover the marketplaces or stores a single seller-account credential
     * grants access to (Req 6.1). Optional capability; supported by platforms
     * whose credentials span multiple marketplaces (e.g. Amazon).
     *
     * @throws UnsupportedOperationException if the platform does not support
     *                                       store discovery
     */
    default List<DiscoveredStore> discoverStores(ConnectionContext ctx) {
        throw new UnsupportedOperationException(
                "Platform '" + platform() + "' does not support store discovery");
    }
}
