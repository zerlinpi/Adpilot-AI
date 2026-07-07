// Feature: app-functionality-completion — Login log status filter wiring.
//
// Verifies the login-logs status filter is wired to the backend
// GET /api/login-logs?status=... rather than filtered only on the client.
//
// Validates: Requirements 15.4

import { describe, it, expect } from 'vitest';
import { buildLoginLogsQuery } from './LoginLogsPage';

describe('buildLoginLogsQuery', () => {
  it('omits the status param for 全部 (all)', () => {
    const params = new URLSearchParams(buildLoginLogsQuery('全部'));
    expect(params.has('status')).toBe(false);
    expect(params.get('pageSize')).toBe('200');
  });

  it('maps 成功 to status=success', () => {
    const params = new URLSearchParams(buildLoginLogsQuery('成功'));
    expect(params.get('status')).toBe('success');
  });

  it('maps 失败 to status=failed', () => {
    const params = new URLSearchParams(buildLoginLogsQuery('失败'));
    expect(params.get('status')).toBe('failed');
  });

  it('omits the status param for an unknown label', () => {
    const params = new URLSearchParams(buildLoginLogsQuery('unknown'));
    expect(params.has('status')).toBe(false);
  });
});
