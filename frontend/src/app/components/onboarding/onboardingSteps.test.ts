// Unit tests for the pure onboarding step-status computation.
//
// Validates that step completion is derived from REAL data (connections /
// products / goals / hosted campaigns) and that the "current" pointer is always
// the first incomplete step, never hardcoded.

import { describe, it, expect } from 'vitest';
import {
  computeOnboardingSteps,
  computeStepCompletion,
  currentOnboardingStepId,
  isConnectionConnected,
  isOnboardingComplete,
  ONBOARDING_STEP_ORDER,
  type OnboardingData,
} from './onboardingSteps';

const connected = { storeId: 's1', platform: 'amazon_ads', status: 'connected' };
const disconnected = { storeId: 's1', platform: 'amazon_ads', status: 'disconnected' };

describe('isConnectionConnected', () => {
  it('treats connected/active/authorized/ok/healthy as connected', () => {
    for (const status of ['connected', 'active', 'authorized', 'ok', 'healthy', 'CONNECTED']) {
      expect(isConnectionConnected({ status })).toBe(true);
    }
  });

  it('treats other / missing statuses as not connected', () => {
    for (const status of ['disconnected', 'config_error', 'token_expired', '', undefined]) {
      expect(isConnectionConnected({ status })).toBe(false);
    }
  });

  it('falls back to connectionStatus / state fields', () => {
    expect(isConnectionConnected({ connectionStatus: 'connected' })).toBe(true);
    expect(isConnectionConnected({ state: 'active' })).toBe(true);
  });
});

describe('computeStepCompletion', () => {
  it('marks every step incomplete for a brand-new user', () => {
    const data: OnboardingData = {};
    expect(computeStepCompletion(data)).toEqual({ connect: false, sync: false, goal: false });
  });

  it('marks connect done only when a connected connection exists', () => {
    expect(computeStepCompletion({ connections: [disconnected] }).connect).toBe(false);
    expect(computeStepCompletion({ connections: [disconnected, connected] }).connect).toBe(true);
  });

  it('marks sync done when products OR sync records exist', () => {
    expect(computeStepCompletion({ products: [{ id: 'p1' }] }).sync).toBe(true);
    expect(computeStepCompletion({ syncRecords: [{ id: 'j1' }] }).sync).toBe(true);
    expect(computeStepCompletion({ products: [], syncRecords: [] }).sync).toBe(false);
  });

  it('marks goal done when a goal OR a hosted campaign exists', () => {
    expect(computeStepCompletion({ goals: [{ id: 'g1' }] }).goal).toBe(true);
    expect(computeStepCompletion({ hostedCampaigns: [{ id: 'c1' }] }).goal).toBe(true);
    expect(computeStepCompletion({ goals: [] }).goal).toBe(false);
  });
});

describe('computeOnboardingSteps', () => {
  it('returns the three steps in order', () => {
    const steps = computeOnboardingSteps({});
    expect(steps.map((s) => s.id)).toEqual(ONBOARDING_STEP_ORDER);
  });

  it('points "current" at the first step for a new user, rest pending', () => {
    const steps = computeOnboardingSteps({});
    expect(steps.map((s) => s.status)).toEqual(['current', 'pending', 'pending']);
  });

  it('advances the current pointer past completed steps', () => {
    // Connected but no products and no goals → step 1 done, step 2 current.
    const steps = computeOnboardingSteps({ connections: [connected] });
    expect(steps.map((s) => s.status)).toEqual(['done', 'current', 'pending']);
  });

  it('marks the third step current once the first two are done', () => {
    const steps = computeOnboardingSteps({
      connections: [connected],
      products: [{ id: 'p1' }],
    });
    expect(steps.map((s) => s.status)).toEqual(['done', 'done', 'current']);
  });

  it('marks all steps done (none current) when fully set up', () => {
    const steps = computeOnboardingSteps({
      connections: [connected],
      products: [{ id: 'p1' }],
      goals: [{ id: 'g1' }],
    });
    expect(steps.map((s) => s.status)).toEqual(['done', 'done', 'done']);
    expect(steps.every((s) => s.done)).toBe(true);
  });

  it('treats a later-completed step as done even if an earlier one is incomplete', () => {
    // Goal exists but no connection/products: step 3 is done, step 1 is current.
    const steps = computeOnboardingSteps({ goals: [{ id: 'g1' }] });
    expect(steps.map((s) => s.status)).toEqual(['current', 'pending', 'done']);
  });
});

describe('isOnboardingComplete / currentOnboardingStepId', () => {
  it('is complete only when all steps are done', () => {
    expect(isOnboardingComplete({})).toBe(false);
    expect(
      isOnboardingComplete({
        connections: [connected],
        products: [{ id: 'p1' }],
        goals: [{ id: 'g1' }],
      }),
    ).toBe(true);
  });

  it('reports the first incomplete step id, or null when complete', () => {
    expect(currentOnboardingStepId({})).toBe('connect');
    expect(currentOnboardingStepId({ connections: [connected] })).toBe('sync');
    expect(
      currentOnboardingStepId({
        connections: [connected],
        products: [{ id: 'p1' }],
        goals: [{ id: 'g1' }],
      }),
    ).toBeNull();
  });
});
