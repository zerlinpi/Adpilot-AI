// Feature: platform-workspace-rbac, Property 23: API client surfaces every
// error response.
//
// For any error HTTP status returned to the shared frontend API client, the
// client surfaces an error indication to the operator (it rejects with an
// Error) rather than resolving silently with data. This holds regardless of
// whether the error response carries a JSON error envelope, a JSON body that
// is not a well-formed envelope, or a non-JSON body (HTML error page, empty
// body, proxy/gateway fallback). It also holds for a `success: false` envelope
// returned with an otherwise-OK HTTP status.
//
// Validates: Requirements 17.4
//
// `request()` is not exported, so we exercise it through `fetchCurrentUser`,
// the thinnest exported wrapper (`request<T>('/auth/me')`). We mock `authFetch`
// — the single collaborator `request()` calls — to return a faithful
// Response-like object whose `json()` runs the real `JSON.parse(body)`, so it
// behaves exactly as a real `fetch` Response does. No network, no fabricated
// behavior.

import { describe, it, expect, vi, beforeEach } from 'vitest';
import fc from 'fast-check';

// Control `request()`'s only collaborator. The real `authFetch` performs a
// network fetch and a 401 logout redirect; neither is relevant to the
// error-surfacing path under test, so we stub it to hand back the Response we
// generate for each example.
vi.mock('./auth', () => ({
  authFetch: vi.fn(),
}));

import { authFetch } from './auth';
import { fetchCurrentUser } from './api';

const RUNS = 200; // >= 100 iterations as required for property tests.

/**
 * Build a faithful Response-like object. `json()` runs the real `JSON.parse`,
 * so it rejects precisely when `body` is not valid JSON — mirroring how a real
 * `fetch` Response behaves for an HTML error page or empty body. `ok` is
 * derived from the status exactly like the platform `Response`.
 */
function makeResponse(status: number, statusText: string, body: string): Response {
  return {
    status,
    statusText,
    ok: status >= 200 && status < 300,
    json: async () => JSON.parse(body),
  } as unknown as Response;
}

// Any error HTTP status (4xx/5xx). The client must surface an error indication
// for every one of these rather than resolving silently.
const errorStatusArb = fc.integer({ min: 400, max: 599 });

// A spread of bodies an error response can realistically carry. Every variant
// must still produce a rejection:
//  - a well-formed `success: false` error envelope (with/without a message),
//  - a `success: true` envelope inconsistently paired with an error status,
//  - JSON that is not an envelope at all (object, array, primitive),
//  - non-JSON bodies (HTML error page, empty body, stray markup).
const errorBodyArb = fc.oneof(
  // success:false envelope with a backend message.
  fc
    .string({ minLength: 1, maxLength: 40 })
    .map((m) => JSON.stringify({ success: false, error: { code: 'ERR', message: m } })),
  // success:false envelope with no message field.
  fc.constant(JSON.stringify({ success: false })),
  // success:true envelope incorrectly returned with an error status — the
  // client must still reject because the HTTP status is not OK.
  fc.constant(JSON.stringify({ success: true, data: { id: 'x' } })),
  // Valid JSON that is not an envelope.
  fc.constantFrom('{}', '[]', '123', '"oops"', 'null'),
  // Non-JSON bodies.
  fc.constant('<html><body><h1>500 Internal Server Error</h1></body></html>'),
  fc.constant('<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head></html>'),
  fc.constant(''),
  fc.constant('   '),
);

const statusTextArb = fc.constantFrom(
  '',
  'Bad Request',
  'Unauthorized',
  'Forbidden',
  'Not Found',
  'Internal Server Error',
  'Bad Gateway',
  'Service Unavailable',
);

describe('Feature: platform-workspace-rbac, Property 23: API client surfaces every error response', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('rejects with an Error for any error HTTP status and any body, never resolving silently', async () => {
    await fc.assert(
      fc.asyncProperty(
        errorStatusArb,
        statusTextArb,
        errorBodyArb,
        async (status, statusText, body) => {
          (authFetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
            makeResponse(status, statusText, body),
          );

          let caught: unknown;
          let resolved: unknown;
          let didResolve = false;
          try {
            resolved = await fetchCurrentUser();
            didResolve = true;
          } catch (e) {
            caught = e;
          }

          // The client must NOT resolve silently for an error status.
          expect(didResolve).toBe(false);
          // It surfaces an error indication: a thrown Error with a message.
          expect(caught).toBeInstanceOf(Error);
          expect((caught as Error).message.length).toBeGreaterThan(0);
          // No data leaks back to the caller on the error path.
          expect(resolved).toBeUndefined();
        },
      ),
      { numRuns: RUNS },
    );
  });

  it('rejects a success:false envelope even when the HTTP status is OK (no silent resolve)', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.integer({ min: 200, max: 299 }),
        fc.option(fc.string({ minLength: 1, maxLength: 40 }), { nil: undefined }),
        async (status, message) => {
          const body = JSON.stringify({
            success: false,
            ...(message !== undefined ? { error: { code: 'ERR', message } } : {}),
          });
          (authFetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
            makeResponse(status, 'OK', body),
          );

          let caught: unknown;
          let didResolve = false;
          try {
            await fetchCurrentUser();
            didResolve = true;
          } catch (e) {
            caught = e;
          }

          expect(didResolve).toBe(false);
          expect(caught).toBeInstanceOf(Error);
        },
      ),
      { numRuns: RUNS },
    );
  });
});
