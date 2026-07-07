// Unit tests for the AI Listing studio product-id resolution and the
// "select a product" prompt shown when no product is resolvable.
// Validates: Requirements 7.1, 7.6

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router';

import {
  ListingAIPage,
  resolveListingProductId,
  buildProductUploadHref,
} from './ListingAIPage';

// Isolate the page from the real network layer so the component tests observe
// only resolution/prompt behavior.
vi.mock('../lib/api', () => ({
  fetchListingContent: vi.fn().mockResolvedValue(null),
  generateListingDraft: vi.fn().mockResolvedValue(null),
  scoreListing: vi.fn().mockResolvedValue(null),
  checkListingCompliance: vi.fn().mockResolvedValue(null),
  fetchKeywordMapping: vi.fn().mockResolvedValue([]),
  fetchListingVersions: vi.fn().mockResolvedValue([]),
  updateListingDraft: vi.fn().mockResolvedValue(null),
  approveListingDraft: vi.fn().mockResolvedValue(null),
}));

import {
  fetchListingContent,
  fetchKeywordMapping,
  fetchListingVersions,
} from '../lib/api';

const VALID_UUID = 'a3f1c2d4-5b6e-4f80-9a1b-2c3d4e5f6071';

describe('resolveListingProductId', () => {
  it('returns null when the route param is missing', () => {
    expect(resolveListingProductId(undefined)).toBeNull();
    expect(resolveListingProductId(null)).toBeNull();
    expect(resolveListingProductId('')).toBeNull();
    expect(resolveListingProductId('   ')).toBeNull();
  });

  it('treats the sidebar placeholder "new" as no product (Req 7.6)', () => {
    expect(resolveListingProductId('new')).toBeNull();
    expect(resolveListingProductId('NEW')).toBeNull();
  });

  it('treats the legacy hardcoded "product-1" fallback as no product (Req 7.6)', () => {
    expect(resolveListingProductId('product-1')).toBeNull();
    expect(resolveListingProductId('Product-1')).toBeNull();
  });

  it('returns null for a non-UUID identifier', () => {
    expect(resolveListingProductId('abc')).toBeNull();
    expect(resolveListingProductId('12345')).toBeNull();
    expect(resolveListingProductId('not-a-uuid-value')).toBeNull();
  });

  it('resolves a valid product UUID and trims surrounding whitespace (Req 7.1)', () => {
    expect(resolveListingProductId(VALID_UUID)).toBe(VALID_UUID);
    expect(resolveListingProductId(`  ${VALID_UUID}  `)).toBe(VALID_UUID);
  });

  it('accepts a UUID regardless of letter case', () => {
    expect(resolveListingProductId(VALID_UUID.toUpperCase())).toBe(
      VALID_UUID.toUpperCase(),
    );
  });
});

/** Render the studio at products/:id/listing-ai for the given route id. */
function renderStudio(routeId: string) {
  return render(
    <MemoryRouter initialEntries={[`/products/${routeId}/listing-ai`]}>
      <Routes>
        <Route path="/products/:id/listing-ai" element={<ListingAIPage />} />
        <Route path="/products" element={<div>商品列表页</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ListingAIPage product resolution', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('prompts to select a product and skips the listing API when none is resolvable (Req 7.6)', async () => {
    renderStudio('new');

    expect(await screen.findByText('请先选择一个产品')).toBeInTheDocument();
    // The non-functional "ASIN: N/A" studio header must not be rendered.
    expect(screen.queryByText(/ASIN:/)).not.toBeInTheDocument();
    // No data is requested when there is no resolvable product.
    expect(fetchListingContent).not.toHaveBeenCalled();
    expect(fetchKeywordMapping).not.toHaveBeenCalled();
    expect(fetchListingVersions).not.toHaveBeenCalled();
  });

  it('passes the resolved product UUID to the listing endpoints (Req 7.1)', async () => {
    renderStudio(VALID_UUID);

    await waitFor(() => {
      expect(fetchListingContent).toHaveBeenCalledWith(VALID_UUID);
    });
    expect(fetchKeywordMapping).toHaveBeenCalledWith(VALID_UUID);
    expect(fetchListingVersions).toHaveBeenCalledWith(VALID_UUID);
    // With a resolvable product the select-a-product prompt is not shown.
    expect(screen.queryByText('请先选择一个产品')).not.toBeInTheDocument();
  });
});

// Validates: Requirements 8.1, 8.2
describe('buildProductUploadHref', () => {
  it('targets the product upload route carrying the product id (Req 8.1, 8.2)', () => {
    expect(buildProductUploadHref(VALID_UUID)).toBe(
      `/product-upload?productId=${VALID_UUID}`,
    );
  });

  it('carries the marketplace context when provided (Req 8.2)', () => {
    expect(buildProductUploadHref(VALID_UUID, 'mp-1')).toBe(
      `/product-upload?productId=${VALID_UUID}&marketplaceId=mp-1`,
    );
  });

  it('omits absent context and falls back to the bare route', () => {
    expect(buildProductUploadHref(VALID_UUID, null)).toBe(
      `/product-upload?productId=${VALID_UUID}`,
    );
    expect(buildProductUploadHref('')).toBe('/product-upload');
  });
});

describe('ListingAIPage product upload entry point', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders an upload entry link that navigates to the upload workflow with product context (Req 8.1, 8.2)', async () => {
    renderStudio(VALID_UUID);

    const uploadLink = await screen.findByRole('link', { name: /渠道发布/ });
    expect(uploadLink).toHaveAttribute(
      'href',
      `/product-upload?productId=${VALID_UUID}`,
    );
  });
});
