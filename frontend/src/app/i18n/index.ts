import { menu } from './menu';
import { enums } from './enums';
import { errors } from './errors';
import { forms } from './forms';
import { pages } from './pages';
import { toast } from './toast';
import { actions } from './actions';

export const i18n = { menu, enums, errors, forms, pages, toast, actions };
export type I18nKey = keyof typeof i18n;

/** Tracks keys already reported missing so the dev console isn't spammed with
 *  the same warning on every render. */
const reportedMissingKeys = new Set<string>();

/** True when running under Vite's dev server. Guarded so production builds never
 *  pay the cost of (or leak) missing-key diagnostics. */
function isDev(): boolean {
  try {
    // `import.meta.env.DEV` is statically replaced by Vite; the try/catch keeps
    // this safe in non-Vite contexts (e.g. unit tests under Node).
    return Boolean((import.meta as any)?.env?.DEV);
  } catch {
    return false;
  }
}

/**
 * Surface a missing Localization_Catalog key during development (Req 17.4) so it
 * can be added to the catalog, while degrading gracefully in production by
 * returning the key itself as a last-resort label.
 */
function reportMissingKey(key: string): void {
  if (!isDev() || reportedMissingKeys.has(key)) return;
  reportedMissingKeys.add(key);
  // eslint-disable-next-line no-console
  console.warn(`[i18n] Missing localization key: "${key}". Add it to the catalog under frontend/src/app/i18n/.`);
}

/** Test-only hook to reset the dedup set between cases. */
export function __resetMissingKeyTracking(): void {
  reportedMissingKeys.clear();
}

/**
 * Resolve a dotted catalog key (e.g. `pages.campaigns.title`) to its Chinese
 * string. Supports `{name}` placeholder interpolation via `vars`.
 *
 * When a key cannot be resolved to a string, the missing key is surfaced during
 * development (Req 17.4) and the key itself is returned so the UI never renders
 * `undefined`.
 */
export function t(key: string, vars?: Record<string, string | number>): string {
  const parts = key.split('.');
  let result: any = i18n;
  for (const part of parts) {
    result = result?.[part];
  }
  if (typeof result !== 'string') {
    reportMissingKey(key);
    return key;
  }
  return vars ? interpolate(result, vars) : result;
}

/** Replace `{token}` placeholders in a catalog string with provided values. */
function interpolate(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (match, token) =>
    Object.prototype.hasOwnProperty.call(vars, token) ? String(vars[token]) : match,
  );
}
