// Unit tests for the pure advertising permission matrix.
//
// Confirms the Requirement 27.1 matrix codes, the N/A audit-delete cells, and
// the `canShowControl` "iff the set contains the matrix code" predicate.
//
// Validates: Requirements 26.1, 26.2, 26.4, 27.7

import { describe, it, expect } from 'vitest';
import {
  ADVERTISING_PERMISSION_MATRIX,
  canShowControl,
  permissionCodeForAction,
  type AdvertisingAction,
  type AdvertisingResource,
} from './advertisingPermissionMatrix';

const ALL_RESOURCES = Object.keys(
  ADVERTISING_PERMISSION_MATRIX,
) as AdvertisingResource[];
const ALL_ACTIONS: AdvertisingAction[] = [
  'view',
  'create-edit',
  'delete',
  'approve',
  'platform-execute',
];

describe('permissionCodeForAction — Requirement 27.1 matrix', () => {
  it('maps the advertising-default resources to advertising:* codes', () => {
    expect(permissionCodeForAction('Campaign', 'view')).toBe('advertising:view');
    expect(permissionCodeForAction('Campaign', 'create-edit')).toBe('advertising:manage');
    expect(permissionCodeForAction('Campaign', 'delete')).toBe('advertising:manage');
    expect(permissionCodeForAction('Campaign', 'approve')).toBe('advertising:approve');
    expect(permissionCodeForAction('Campaign', 'platform-execute')).toBe('advertising:execute');
  });

  it('maps the keyword-family resources to keyword:* codes', () => {
    for (const r of ['Target', 'Keyword', 'NegativeKeyword'] as const) {
      expect(permissionCodeForAction(r, 'view')).toBe('keyword:view');
      expect(permissionCodeForAction(r, 'create-edit')).toBe('keyword:manage');
      expect(permissionCodeForAction(r, 'delete')).toBe('keyword:manage');
      expect(permissionCodeForAction(r, 'approve')).toBe('advertising:approve');
      expect(permissionCodeForAction(r, 'platform-execute')).toBe('keyword:apply');
    }
  });

  it('maps SearchTerm view to advertising:view but harvest/execute to keyword codes', () => {
    expect(permissionCodeForAction('SearchTerm', 'view')).toBe('advertising:view');
    expect(permissionCodeForAction('SearchTerm', 'create-edit')).toBe('keyword:manage');
    expect(permissionCodeForAction('SearchTerm', 'platform-execute')).toBe('keyword:apply');
  });

  it('maps Operation view to operation:view and Hosting create-edit to hosting:manage', () => {
    expect(permissionCodeForAction('Operation', 'view')).toBe('operation:view');
    expect(permissionCodeForAction('Hosting', 'create-edit')).toBe('hosting:manage');
    expect(permissionCodeForAction('Hosting', 'delete')).toBe('hosting:manage');
  });

  it('returns null for the audit resources delete cell (N/A, Req 27.3)', () => {
    for (const r of ['Operation', 'SyncLog', 'BidChange'] as const) {
      expect(permissionCodeForAction(r, 'delete')).toBeNull();
    }
  });
});

describe('canShowControl — control shown iff the set holds the matrix code', () => {
  it('shows a control exactly when the permission set contains its matrix code', () => {
    expect(canShowControl(['keyword:manage'], 'Keyword', 'create-edit')).toBe(true);
    expect(canShowControl(['keyword:view'], 'Keyword', 'create-edit')).toBe(false);
    expect(canShowControl([], 'Keyword', 'create-edit')).toBe(false);
  });

  it('accepts a Set as well as an array', () => {
    expect(canShowControl(new Set(['advertising:approve']), 'Recommendation', 'approve')).toBe(true);
    expect(canShowControl(new Set(['advertising:manage']), 'Recommendation', 'approve')).toBe(false);
  });

  it('never shows an N/A audit-delete control even with broad permissions', () => {
    const all = ['advertising:manage', 'advertising:execute', 'advertising:approve'];
    expect(canShowControl(all, 'Operation', 'delete')).toBe(false);
    expect(canShowControl(all, 'SyncLog', 'delete')).toBe(false);
    expect(canShowControl(all, 'BidChange', 'delete')).toBe(false);
  });

  it('for every cell, visibility matches membership of exactly the matrix code', () => {
    for (const resource of ALL_RESOURCES) {
      for (const action of ALL_ACTIONS) {
        const code = permissionCodeForAction(resource, action);
        if (code === null) {
          // N/A cell: never shown regardless of permissions held.
          expect(canShowControl([code ?? 'anything'], resource, action)).toBe(false);
          continue;
        }
        // Holds the exact code -> shown; holds an unrelated code -> hidden.
        expect(canShowControl([code], resource, action)).toBe(true);
        expect(canShowControl(['some:other-code'], resource, action)).toBe(false);
      }
    }
  });
});
