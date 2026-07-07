// AdPilot AI — Unified toast notifications
//
// A thin, app-wide wrapper over sonner so every page reports the outcome of an
// action through ONE consistent channel (replacing the ad-hoc per-page
// flashToast + setTimeout banners). Standardizing on sonner is the UX
// convention mandated by the platform specs. Import `notify` anywhere:
//
//   notify.success('已切换店铺', store.name);
//   notify.error('发布失败', reason);
//
// Keeping it a tiny indirection (rather than importing sonner directly
// everywhere) means the toast library / default options can change in one place.

import { toast as sonnerToast } from 'sonner';

export const notify = {
  /** A successful, completed action. */
  success(message: string, description?: string) {
    return sonnerToast.success(message, description ? { description } : undefined);
  },
  /** A failed action — surfaces the readable reason (honesty: never hide a failure). */
  error(message: string, description?: string) {
    return sonnerToast.error(message, description ? { description } : undefined);
  },
  /** Neutral information / acknowledgement. */
  info(message: string, description?: string) {
    return sonnerToast(message, description ? { description } : undefined);
  },
  /** A queued / in-flight action that will resolve asynchronously. */
  loading(message: string, description?: string) {
    return sonnerToast.loading(message, description ? { description } : undefined);
  },
};

export type Notify = typeof notify;
