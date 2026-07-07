// Feature: app-functionality-completion, Property 2: Frontend renders a
// readable error for any non-JSON body.
//
// For any HTTP status and any non-JSON response body, the shared `request()`
// helper in `lib/api.ts` produces an error whose message includes the HTTP
// status and a fallback description ("API error"), and never surfaces the raw
// JSON parse error text (e.g. `Unexpected token '<', "<html>..." is not valid
// JSON`) to the caller.
//
// Validates: Requirements 1.4
//
// `request()` is not exported, so we exercise it through `fetchCurrentUser`,
// the thinnest exported wrapper (`request<T>('/auth/me')`). We mock `authFetch`
// — the single dependency `request()` calls — to return a faithful Response-like
// object whose `json()` genuinely runs `JSON.parse(body)` and therefore throws
// exactly as a real `fetch` Response does on a non-JSON body. No network, no
// fabricated parser behavior.

import { describe, it, expect, vi, beforeEach } from 'vitest';
import fc from 'fast-check';

// Mock the auth module so `request()`'s only collaborator is controlled. The
// real `authFetch` performs a network fetch and a 401/403 logout side effect;
// neither is relevant to the parse-error path under test.
vi.mock('./auth', () => ({
  authFetch: vi.fn(),
}));

import { authFetch } from './auth';
import { fetchCurrentUser } from './api';

const RUNS = 200; // >= 100 iterations as required for property tests.

/**
 * Build a faithful Response-like object. `json()` runs the real `JSON.parse`,
 * so it rejects precisely when `body` is not valid JSON — mirroring how a real
 * `fetch` Response behaves for an HTML error page or empty body.
 */
function makeResponse(status: number, statusText: string, body: string): Response {
  return {
    status,
    statusText,
    ok: status >= 200 && status < 300,
    json: async () => JSON.parse(body),
  } as unknown as Response;
}

// Any HTTP status. We exclude 401/403 because the real `authFetch` short-circuits
// those with a logout before `request()` ever parses a body; the non-JSON path
// under test is reached for every other status.
const statusArb = fc.integer({ min: 100, max: 599 }).filter((s) => s !== 401 && s !== 403);

// Realistic HTTP reason phrases plus arbitrary text, including the empty string
// (a body with no reason phrase). None of these contain the substring "JSON",
// so a positive "no parse text" assertion can't be satisfied by the status text.
const statusTextArb = fc.oneof(
  fc.constantFrom(
    '',
    'OK',
    'Not Found',
    'Internal Server Error',
    'Bad Gateway',
    'Service Unavailable',
    'Gateway Timeout',
  ),
  fc.stringMatching(/^[a-zA-Z ]{0,20}$/),
);

// Non-JSON bodies: HTML error pages, empty bodies, stray markup and arbitrary
// strings. Filtered so only genuinely non-JSON bodies survive (the property is
// specifically about bodies that fail to parse).
const nonJsonBodyArb = fc
  .oneof(
    fc.constant('<html><body><h1>500 Internal Server Error</h1></body></html>'),
    fc.constant('<!DOCTYPE html><html><head><title>Error</title></head></html>'),
    fc.constant('<html>Unexpected token nginx proxy fallback</html>'),
    fc.constant(''),
    fc.constant('   '),
    fc.string(),
    fc.string().map((s) => `<${s}`),
  )
  .filter((s) => {
    try {
      JSON.parse(s);
      return false; // valid JSON — not a subject of this property
    } catch {
      return true;
    }
  });

describe('Feature: app-functionality-completion, Property 2: Frontend renders a readable error for any non-JSON body', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('produces a readable error with the HTTP status and a fallback description, never the raw parse text', async () => {
    await fc.assert(
      fc.asyncProperty(statusArb, statusTextArb, nonJsonBodyArb, async (status, statusText, body) => {
        (authFetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
          makeResponse(status, statusText, body),
        );

        // Capture the raw parse error a naive implementation would have leaked,
        // so we can assert it never appears in the user-facing message.
        let rawParseText = '';
        try {
          JSON.parse(body);
        } catch (e) {
          rawParseText = (e as Error).message;
        }

        let caught: unknown;
        try {
          await fetchCurrentUser();
        } catch (e) {
          caught = e;
        }

        // The helper must reject (a non-JSON body is always an error).
        expect(caught).toBeInstanceOf(Error);
        const msg = (caught as Error).message;

        // Fallback description present.
        expect(msg.startsWith('API error:')).toBe(true);
        // The HTTP status is included.
        expect(msg).toContain(String(status));
        // The raw JSON parse error text is never surfaced.
        expect(rawParseText.length).toBeGreaterThan(0);
        expect(msg).not.toContain(rawParseText);
        expect(msg).not.toMatch(/Unexpected token|is not valid JSON|Unexpected end of JSON input/i);
      }),
      { numRuns: RUNS },
    );
  });

  it('appends the status text when present and omits it cleanly when blank', async () => {
    await fc.assert(
      fc.asyncProperty(statusArb, nonJsonBodyArb, async (status, body) => {
        // Non-blank, JSON-free reason phrase.
        const statusText = 'Bad Gateway';
        (authFetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
          makeResponse(status, statusText, body),
        );

        let caught: unknown;
        try {
          await fetchCurrentUser();
        } catch (e) {
          caught = e;
        }

        expect(caught).toBeInstanceOf(Error);
        expect((caught as Error).message).toBe(`API error: ${status} ${statusText}`);
      }),
      { numRuns: RUNS },
    );
  });
});
