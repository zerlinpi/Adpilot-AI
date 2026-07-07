// Unit tests for the per-product AI ad creation modal's client-side validation
// (Task 11.6). Covers the instant field-level prompts and submission blocking
// required when budget / target ACoS / bid / budget bounds are non-positive or an
// upper bound is smaller than its lower bound.
// Validates: Requirements 1.4

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  validateProductAdForm,
  emptyProductAdForm,
  EXECUTION_MODE_UNSET,
  type ProductAdFormValues,
} from './ProductAdModal';

/** A fully-valid form: required positive budget, no optional bounds supplied. */
function validForm(overrides: Partial<ProductAdFormValues> = {}): ProductAdFormValues {
  return { ...emptyProductAdForm(), budget: '50', ...overrides };
}

/** Field names that carry an error in the returned list. */
function fields(errors: { field: string }[]): string[] {
  return errors.map((e) => e.field);
}

describe('validateProductAdForm (Req 1.4)', () => {
  it('accepts a valid form with only the required budget filled', () => {
    expect(validateProductAdForm(validForm())).toEqual([]);
  });

  it('accepts a valid form with all optional bounds within range', () => {
    const errors = validateProductAdForm(
      validForm({
        targetAcos: '25',
        bidMin: '0.5',
        bidMax: '2',
        budgetMin: '10',
        budgetMax: '100',
      }),
    );
    expect(errors).toEqual([]);
  });

  it('requires the budget — blank budget yields a budget error', () => {
    const errors = validateProductAdForm(validForm({ budget: '' }));
    expect(fields(errors)).toContain('budget');
  });

  it('rejects a non-positive budget (zero and negative)', () => {
    expect(fields(validateProductAdForm(validForm({ budget: '0' })))).toContain('budget');
    expect(fields(validateProductAdForm(validForm({ budget: '-5' })))).toContain('budget');
  });

  it('rejects a non-numeric budget', () => {
    expect(fields(validateProductAdForm(validForm({ budget: 'abc' })))).toContain('budget');
  });

  it.each([
    ['targetAcos'],
    ['bidMin'],
    ['bidMax'],
    ['budgetMin'],
    ['budgetMax'],
  ] as const)('rejects a non-positive %s when supplied', (field) => {
    expect(fields(validateProductAdForm(validForm({ [field]: '0' })))).toContain(field);
    expect(fields(validateProductAdForm(validForm({ [field]: '-1' })))).toContain(field);
  });

  it('treats blank optional bounds as "use parent default" (no error)', () => {
    const errors = validateProductAdForm(
      validForm({ targetAcos: '', bidMin: '', bidMax: '', budgetMin: '', budgetMax: '' }),
    );
    expect(errors).toEqual([]);
  });

  it('rejects a bid range whose upper bound is smaller than its lower bound', () => {
    const errors = validateProductAdForm(validForm({ bidMin: '2', bidMax: '1' }));
    expect(fields(errors)).toContain('bidMax');
  });

  it('rejects a budget range whose upper bound is smaller than its lower bound', () => {
    const errors = validateProductAdForm(validForm({ budgetMin: '100', budgetMax: '10' }));
    expect(fields(errors)).toContain('budgetMax');
  });

  it('allows an equal upper and lower bound (upper not smaller than lower)', () => {
    const errors = validateProductAdForm(
      validForm({ bidMin: '1', bidMax: '1', budgetMin: '50', budgetMax: '50' }),
    );
    expect(errors).toEqual([]);
  });

  it('does not raise an upper<lower error when a bound is itself invalid', () => {
    // bidMin is invalid (non-positive), so no cross-field comparison is attempted.
    const errors = validateProductAdForm(validForm({ bidMin: '-1', bidMax: '0.5' }));
    expect(fields(errors)).toContain('bidMin');
    // The only bidMax-related entry would be the bound comparison, which is skipped.
    expect(fields(errors)).not.toContain('bidMax');
  });

  it('reports one error per offending field across multiple violations', () => {
    const errors = validateProductAdForm(
      validForm({ budget: '-1', targetAcos: '-2', bidMin: '3', bidMax: '1' }),
    );
    const offending = fields(errors);
    expect(offending).toContain('budget');
    expect(offending).toContain('targetAcos');
    expect(offending).toContain('bidMax');
  });
});

// Render-level test: an invalid submit surfaces the inline error and blocks the
// network call to createProductAdCampaign (Req 1.4 — submission blocked).
const createProductAdCampaign = vi.fn();

vi.mock('../lib/api', () => ({
  createProductAdCampaign: (...args: unknown[]) => createProductAdCampaign(...args),
}));

vi.mock('../lib/PermissionContext', () => ({
  usePermissions: () => ({
    platformAccess: { superAdmin: true, families: [] },
    can: () => true,
    canAny: () => true,
    canAll: () => true,
  }),
}));

describe('ProductAdModal submission blocking (Req 1.4)', () => {
  beforeEach(() => {
    createProductAdCampaign.mockReset();
  });

  async function openModal() {
    // Imported lazily so the module-level mocks above are applied first.
    const { ProductAdModal } = await import('./ProductAdModal');
    const user = userEvent.setup();
    render(
      <ProductAdModal
        storeId="store-1"
        productId="prod-1"
        platformFamily="amazon"
        triggerLabel="打开广告弹窗"
      />,
    );
    await user.click(screen.getByRole('button', { name: '打开广告弹窗' }));
    return user;
  }

  it('shows an inline error and does not call the API when budget is invalid', async () => {
    const user = await openModal();

    const budget = screen.getByLabelText(/预算金额/);
    await user.clear(budget);
    await user.type(budget, '-5');

    await user.click(screen.getByRole('button', { name: '创建广告' }));

    expect(await screen.findByText('预算必须为正数')).toBeInTheDocument();
    expect(createProductAdCampaign).not.toHaveBeenCalled();
  });

  it('blocks submission when an upper bound is smaller than its lower bound', async () => {
    const user = await openModal();

    const budget = screen.getByLabelText(/预算金额/);
    await user.clear(budget);
    await user.type(budget, '50');

    await user.type(screen.getByLabelText('最低出价'), '2');
    await user.type(screen.getByLabelText('最高出价'), '1');

    await user.click(screen.getByRole('button', { name: '创建广告' }));

    expect(await screen.findByText('最高出价不能小于最低出价')).toBeInTheDocument();
    expect(createProductAdCampaign).not.toHaveBeenCalled();
  });

  it('does not block a valid submit (sanity: API is invoked)', async () => {
    createProductAdCampaign.mockResolvedValue({ campaignId: 'c-1', executionMode: EXECUTION_MODE_UNSET });
    const user = await openModal();

    const budget = screen.getByLabelText(/预算金额/);
    await user.clear(budget);
    await user.type(budget, '50');

    await user.click(screen.getByRole('button', { name: '创建广告' }));

    expect(createProductAdCampaign).toHaveBeenCalledTimes(1);
  });
});
