export type NormalizedStorePlatform = 'amazon' | 'shopify' | 'woocommerce' | 'tiktok' | 'unknown';
export type StorePlatformGroup = 'marketplace' | 'independent_site' | 'unknown';
export type StoreAdPlatform = 'amazon_ads' | 'google_ads' | 'tiktok_ads' | 'none';
export type PlatformNavScope = 'all' | 'marketplace' | 'independent_site' | 'amazon' | 'tiktok';

/** The Nav_Block Platform_Family a store belongs to. Each store resolves to
 *  exactly one family so connections, visibility and the store switcher stay
 *  isolated by platform family (multistore-ai-ads-operations Req 6.1, 6.4). */
export type StorePlatformFamily = 'amazon' | 'independent_site' | 'tiktok' | 'unknown';

export function normalizeStorePlatform(platform?: string | null): NormalizedStorePlatform {
  const p = (platform ?? '').trim().toLowerCase();
  if (!p) return 'unknown';
  if (p.startsWith('amazon') || p === 'amz') return 'amazon';
  if (p.startsWith('shopify')) return 'shopify';
  if (p.startsWith('woocommerce') || p === 'woo' || p.startsWith('wp') || p.startsWith('wordpress')) {
    return 'woocommerce';
  }
  if (p.startsWith('tiktok') || p === 'tk') return 'tiktok';
  return 'unknown';
}

export function storePlatformGroup(platform?: string | null): StorePlatformGroup {
  const normalized = normalizeStorePlatform(platform);
  if (normalized === 'shopify' || normalized === 'woocommerce') return 'independent_site';
  if (normalized === 'amazon' || normalized === 'tiktok') return 'marketplace';
  return 'unknown';
}

export function storeAdPlatform(platform?: string | null): StoreAdPlatform {
  const normalized = normalizeStorePlatform(platform);
  if (normalized === 'amazon') return 'amazon_ads';
  if (normalized === 'shopify' || normalized === 'woocommerce') return 'google_ads';
  if (normalized === 'tiktok') return 'tiktok_ads';
  return 'none';
}

export function storePlatformLabel(platform?: string | null): string {
  switch (normalizeStorePlatform(platform)) {
    case 'amazon':
      return 'Amazon';
    case 'shopify':
      return 'Shopify 独立站';
    case 'woocommerce':
      return 'WordPress / WooCommerce 独立站';
    case 'tiktok':
      return 'TikTok Shop';
    default:
      return '未知平台';
  }
}

/**
 * Map a store's platform to its Nav_Block Platform_Family
 * (multistore-ai-ads-operations Req 6.1): Amazon → `amazon`, Shopify /
 * WooCommerce → `independent_site`, TikTok → `tiktok`. An unrecognized platform
 * resolves to `unknown` so it is never grouped into the wrong family.
 */
export function storePlatformFamily(platform?: string | null): StorePlatformFamily {
  const normalized = normalizeStorePlatform(platform);
  if (normalized === 'amazon') return 'amazon';
  if (normalized === 'shopify' || normalized === 'woocommerce') return 'independent_site';
  if (normalized === 'tiktok') return 'tiktok';
  return 'unknown';
}

/** Human-readable label for a {@link StorePlatformFamily}, used as the store
 *  switcher's group heading (multistore-ai-ads-operations Req 6.6). */
export function storePlatformFamilyLabel(family: StorePlatformFamily): string {
  switch (family) {
    case 'amazon':
      return '亚马逊';
    case 'independent_site':
      return '独立站';
    case 'tiktok':
      return 'TikTok';
    default:
      return '未分类店铺';
  }
}

export function storePlatformGroupLabel(platform?: string | null): string {
  switch (storePlatformGroup(platform)) {
    case 'independent_site':
      return '独立站（Shopify / WordPress）';
    case 'marketplace':
      return '平台电商（Amazon / TikTok）';
    default:
      return '未分类店铺';
  }
}

export function adPlatformLabel(adPlatform?: string | null): string {
  switch (adPlatform) {
    case 'amazon_ads':
      return 'Amazon Ads';
    case 'google_ads':
      return 'Google Ads';
    case 'tiktok_ads':
      return 'TikTok Ads';
    default:
      return '未配置广告平台';
  }
}

export function navScopeMatchesStore(scope: PlatformNavScope | undefined, platform?: string | null): boolean {
  if (!scope || scope === 'all') return true;
  const normalized = normalizeStorePlatform(platform);
  if (scope === 'amazon') return normalized === 'amazon';
  if (scope === 'tiktok') return normalized === 'tiktok';
  return storePlatformGroup(platform) === scope;
}
