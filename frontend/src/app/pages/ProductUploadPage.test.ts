// Unit tests for the product upload navigation entry point.
//
// Covers the navigation target and the context carried into the upload
// workflow on navigation.
// Validates: Requirements 8.1, 8.2

import { describe, it, expect } from 'vitest';
import {
  resolveUploadContext,
  PRODUCT_UPLOAD_ROUTE,
  type UploadContextParams,
} from './ProductUploadPage';

/** Build a URLSearchParams-like source from a plain record. */
function params(record: Record<string, string> = {}): UploadContextParams {
  return { get: (key: string) => (key in record ? record[key] : null) };
}

describe('PRODUCT_UPLOAD_ROUTE', () => {
  it('targets the product upload workflow route (Req 8.1)', () => {
    expect(PRODUCT_UPLOAD_ROUTE).toBe('/product-upload');
  });
});

describe('resolveUploadContext', () => {
  it('reads product and marketplace context from query params (Req 8.2)', () => {
    const ctx = resolveUploadContext(
      params({ productId: 'p-123', marketplaceId: 'mp-1' }),
      null,
    );
    expect(ctx).toEqual({ productId: 'p-123', marketplaceId: 'mp-1' });
  });

  it('falls back to router location state when query params are absent (Req 8.2)', () => {
    const ctx = resolveUploadContext(params({}), {
      productId: 'p-state',
      marketplaceId: 'mp-state',
    });
    expect(ctx).toEqual({ productId: 'p-state', marketplaceId: 'mp-state' });
  });

  it('prefers query params over location state when both are present', () => {
    const ctx = resolveUploadContext(
      params({ productId: 'p-query', marketplaceId: 'mp-query' }),
      { productId: 'p-state', marketplaceId: 'mp-state' },
    );
    expect(ctx).toEqual({ productId: 'p-query', marketplaceId: 'mp-query' });
  });

  it('resolves product and marketplace independently from mixed sources', () => {
    const ctx = resolveUploadContext(params({ productId: 'p-query' }), {
      marketplaceId: 'mp-state',
    });
    expect(ctx).toEqual({ productId: 'p-query', marketplaceId: 'mp-state' });
  });

  it('returns empty strings when no context is provided', () => {
    expect(resolveUploadContext(params({}), null)).toEqual({
      productId: '',
      marketplaceId: '',
    });
    expect(resolveUploadContext(params({}))).toEqual({
      productId: '',
      marketplaceId: '',
    });
  });

  it('treats an empty query value as absent and falls through to state', () => {
    const ctx = resolveUploadContext(params({ productId: '' }), {
      productId: 'p-state',
    });
    expect(ctx.productId).toBe('p-state');
  });
});
