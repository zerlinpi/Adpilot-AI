// AdPilot AI - Auth utilities

const ACCESS_TOKEN_KEY = 'adpilot_access_token';
const REFRESH_TOKEN_KEY = 'adpilot_refresh_token';
const CURRENT_USER_KEY = 'adpilot_current_user';

// The frontend issues calls against relative `/api/...` paths by default.
// Set VITE_API_BASE_URL when the backend is served from a different origin.
const API_ORIGIN = String((import.meta as any)?.env?.VITE_API_BASE_URL || '').replace(/\/+$/, '');

/** Resolve a request path to a full URL, prefixing the configured API origin. */
export function apiUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path;
  const p = path.startsWith('/') ? path : `/${path}`;
  return `${API_ORIGIN}${p}`;
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function setAccessToken(token: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, token);
}

export function removeAccessToken(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setRefreshToken(token: string): void {
  localStorage.setItem(REFRESH_TOKEN_KEY, token);
}

export function removeRefreshToken(): void {
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export function getCurrentUser(): any | null {
  const raw = localStorage.getItem(CURRENT_USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function setCurrentUser(user: any): void {
  localStorage.setItem(CURRENT_USER_KEY, JSON.stringify(user));
}

export function removeCurrentUser(): void {
  localStorage.removeItem(CURRENT_USER_KEY);
}

export function isAuthenticated(): boolean {
  return !!getAccessToken();
}

export function logout(): void {
  removeAccessToken();
  removeRefreshToken();
  removeCurrentUser();
  window.location.href = '/login';
}

export async function authFetch(url: string, options?: RequestInit): Promise<Response> {
  const token = getAccessToken();
  const isFormData = typeof FormData !== 'undefined' && options?.body instanceof FormData;
  const headers: Record<string, string> = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(options?.headers as Record<string, string>),
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const res = await fetch(apiUrl(url), { ...options, headers });

  // A 401 means the session/token is no longer valid (auth expiry) — clear it
  // and send the operator to login. A 403 (access denied, e.g. Cross_Platform_
  // Access / Cross_Group access) is NOT an auth expiry: it flows back to the
  // caller so the shared API client surfaces an access-denied error indication
  // rather than silently bouncing to /login (Req 12.3, 17.4).
  if (res.status === 401) {
    logout();
    throw new Error('认证已过期，请重新登录');
  }

  return res;
}
