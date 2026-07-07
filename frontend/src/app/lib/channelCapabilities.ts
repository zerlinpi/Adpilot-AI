import { normalizeStorePlatform, type NormalizedStorePlatform } from './platformTaxonomy';

export interface ChannelCapability {
  platform: NormalizedStorePlatform;
  channelLabel: string;
  adMonitorLabel: string;
  adConnectionPlatform: 'amazon_ads' | 'google_ads' | 'tiktok_ads' | 'none';
  productUploadMethod: 'amazon_flat_file' | 'manual_export';
  productPublishLabel: string;
  contentStudioLabel: string;
  logisticsLabel: string;
  supportsAutomatedAdMonitoring: boolean;
  supportsDirectProductPublish: boolean;
  /**
   * Upload-job method that triggers a real direct publish to the store's
   * connected platform (Shopify / WooCommerce). Present only when
   * supportsDirectProductPublish is true.
   */
  directPublishMethod?: 'shopify_api' | 'woocommerce_api' | 'tiktok_shop_api';
  note: string;
}

const CHANNELS: Record<NormalizedStorePlatform, ChannelCapability> = {
  amazon: {
    platform: 'amazon',
    channelLabel: 'Amazon 店铺',
    adMonitorLabel: 'Amazon Ads AI 监控',
    adConnectionPlatform: 'amazon_ads',
    productUploadMethod: 'amazon_flat_file',
    productPublishLabel: 'Amazon Listing 上传',
    contentStudioLabel: 'Amazon Listing AI',
    logisticsLabel: 'FBA / 仓库 / 物流',
    supportsAutomatedAdMonitoring: true,
    supportsDirectProductPublish: false,
    note: 'Amazon 广告、Listing、FBA 已拆成独立链路；商品发布当前以校验后导出 flat file 为主。',
  },
  shopify: {
    platform: 'shopify',
    channelLabel: 'Shopify 独立站',
    adMonitorLabel: 'Google Ads AI 监控',
    adConnectionPlatform: 'google_ads',
    productUploadMethod: 'manual_export',
    productPublishLabel: '直发 Shopify 商品',
    contentStudioLabel: '独立站商品内容 AI',
    logisticsLabel: '仓库 / 物流 / 履约',
    supportsAutomatedAdMonitoring: false,
    supportsDirectProductPublish: true,
    directPublishMethod: 'shopify_api',
    note: 'Shopify 归入独立站，广告账号走 Google Ads；商品可经 Shopify API 直接发布。',
  },
  woocommerce: {
    platform: 'woocommerce',
    channelLabel: 'WordPress / WooCommerce 独立站',
    adMonitorLabel: 'Google Ads AI 监控',
    adConnectionPlatform: 'google_ads',
    productUploadMethod: 'manual_export',
    productPublishLabel: '直发 WooCommerce 商品',
    contentStudioLabel: '独立站商品内容 AI',
    logisticsLabel: '仓库 / 物流 / 履约',
    supportsAutomatedAdMonitoring: false,
    supportsDirectProductPublish: true,
    directPublishMethod: 'woocommerce_api',
    note: 'WooCommerce 归入独立站，广告账号走 Google Ads；商品可经 WooCommerce API 直接发布。',
  },
  tiktok: {
    platform: 'tiktok',
    channelLabel: 'TikTok Shop',
    adMonitorLabel: 'TikTok Ads AI 监控',
    adConnectionPlatform: 'tiktok_ads',
    productUploadMethod: 'manual_export',
    productPublishLabel: '直发 TikTok 商品',
    contentStudioLabel: 'TikTok 商品内容 AI',
    logisticsLabel: '平台物流 / 仓库 / 履约',
    supportsAutomatedAdMonitoring: false,
    supportsDirectProductPublish: true,
    directPublishMethod: 'tiktok_shop_api',
    note: 'TikTok 商品可经 TikTok Shop API 直接发布；TikTok Ads 与库存/发货回写仍待接入。',
  },
  unknown: {
    platform: 'unknown',
    channelLabel: '未分类店铺',
    adMonitorLabel: '广告 AI 监控',
    adConnectionPlatform: 'none',
    productUploadMethod: 'manual_export',
    productPublishLabel: '导出商品资料',
    contentStudioLabel: '商品内容 AI',
    logisticsLabel: '仓库 / 物流',
    supportsAutomatedAdMonitoring: false,
    supportsDirectProductPublish: false,
    note: '请先为店铺绑定销售平台连接，系统才能判断广告、发布和物流链路。',
  },
};

export function getChannelCapability(platform?: string | null): ChannelCapability {
  return CHANNELS[normalizeStorePlatform(platform)];
}
