// AdPilot AI — First-run onboarding wizard
//
// A guided, dismissible getting-started wizard for new users. It walks through
// three steps (connect → sync → create goal/enable AI hosting), each step's
// status computed from REAL data (see `onboardingSteps.ts`), not hardcoded.
//
// Behavior (Req 2b):
//   - Auto-opens on first login when not all steps are complete AND the user
//     hasn't dismissed it (dismissal persisted in localStorage). Live status is
//     re-checked, so a returning user who finished setup won't see it.
//   - Renders its own small "新手指引" re-open entry, so it can be dropped into a
//     header/toolbar; the modal itself is a fixed overlay independent of where
//     the trigger sits.
//   - Each step has a primary action that navigates to the relevant page; the
//     "current" pointer is the first incomplete step. Completed steps show a
//     green check.
//   - Dismissible (X / "稍后再说"); shows "完成" when all steps are done.
//
// Self-contained: mounting <OnboardingWizard /> does not block the rest of the
// app and degrades gracefully if any data request fails.

import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import {
  ArrowRight,
  CheckCircle2,
  Circle,
  Compass,
  Loader2,
} from 'lucide-react';

import { cn } from '../../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../ui/dialog';
import {
  fetchGoals,
  fetchPlatformConnections,
  fetchProducts,
} from '../../lib/api';
import { useStoreContext } from '../../lib/StoreContext';
import {
  computeOnboardingSteps,
  isOnboardingComplete,
  type OnboardingData,
} from './onboardingSteps';
import { useOnboarding } from './useOnboarding';

export interface OnboardingWizardProps {
  /** Optional extra classes for the trigger button. */
  className?: string;
}

export function OnboardingWizard({ className }: OnboardingWizardProps) {
  const { storeId, loading: storeLoading } = useStoreContext();
  const { dismissed, dismiss, reopen } = useOnboarding();
  const [open, setOpen] = useState(false);
  // Tracks whether the auto-open decision has already run, so re-checks (e.g. a
  // refetch) don't re-open a wizard the user just closed this session.
  const [autoChecked, setAutoChecked] = useState(false);

  const data = useOnboardingData(storeId, storeLoading);

  const steps = useMemo(() => computeOnboardingSteps(data.value), [data.value]);
  const complete = useMemo(() => isOnboardingComplete(data.value), [data.value]);

  // Auto-open once per session: when data is ready, the user hasn't dismissed,
  // and setup is incomplete (Req 2b).
  useEffect(() => {
    if (autoChecked) return;
    if (storeLoading || data.loading) return;
    setAutoChecked(true);
    if (!dismissed && !complete) setOpen(true);
  }, [autoChecked, storeLoading, data.loading, dismissed, complete]);

  const handleClose = () => {
    setOpen(false);
    dismiss();
  };

  const handleReopen = () => {
    reopen();
    setOpen(true);
  };

  return (
    <>
      <button
        type="button"
        onClick={handleReopen}
        className={cn(
          'inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50',
          className,
        )}
      >
        <Compass size={14} />
        新手指引
      </button>

      {open && (
        <OnboardingModal
          steps={steps}
          complete={complete}
          loading={data.loading}
          onClose={handleClose}
        />
      )}
    </>
  );
}

/**
 * Internal: load + combine the wizard's readiness data for a store using manual
 * fetches (no react-query dependency, so the wizard can be mounted anywhere —
 * including hosts that don't provide a QueryClient). Each request is guarded so
 * a failure degrades to an empty list rather than crashing the wizard.
 */
function useOnboardingData(
  storeId: string | null,
  storeLoading: boolean,
): { value: OnboardingData; loading: boolean } {
  const [value, setValue] = useState<OnboardingData>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (storeLoading) return;
    let cancelled = false;

    const guarded = async <T,>(factory: () => Promise<T>, fallback: T): Promise<T> => {
      try {
        return await factory();
      } catch {
        return fallback;
      }
    };

    setLoading(true);
    void (async () => {
      const [connectionsRaw, products, goals] = await Promise.all([
        guarded(() => fetchPlatformConnections(), [] as any[]),
        storeId ? guarded(() => fetchProducts(storeId), [] as any[]) : Promise.resolve([] as any[]),
        storeId ? guarded(() => fetchGoals(storeId), [] as any[]) : Promise.resolve([] as any[]),
      ]);
      if (cancelled) return;
      // Scope connections to the active store when one is selected (mirrors the
      // command center), otherwise consider all of the user's connections.
      const connections = storeId
        ? connectionsRaw.filter((c: any) => !c?.storeId || c.storeId === storeId)
        : connectionsRaw;
      setValue({ connections, products, goals });
      setLoading(false);
    })();

    return () => {
      cancelled = true;
    };
  }, [storeId, storeLoading]);

  return { value, loading };
}

function OnboardingModal({
  steps,
  complete,
  loading,
  onClose,
}: {
  steps: ReturnType<typeof computeOnboardingSteps>;
  complete: boolean;
  loading: boolean;
  onClose: () => void;
}) {
  // Close on Escape, focus trap, focus restore and scroll lock are all provided
  // by the Radix Dialog primitive below.
  const doneCount = steps.filter((s) => s.done).length;

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="block gap-0 p-0 overflow-hidden w-full sm:max-w-lg rounded-2xl border-0 bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <DialogTitle id="onboarding-title" className="text-lg font-semibold text-slate-900">
              {complete ? '设置已完成 🎉' : '欢迎使用 AdPilot，三步开始'}
            </DialogTitle>
            <p className="mt-1 text-sm text-slate-500">
              {complete
                ? '所有准备工作已完成，可以开始日常运营了。'
                : `已完成 ${doneCount} / ${steps.length} 步，跟随指引完成接入。`}
            </p>
          </div>
        </div>

        <div className="space-y-3 px-5 py-4">
          {loading && (
            <div className="flex items-center gap-2 text-sm text-slate-500">
              <Loader2 size={16} className="animate-spin" />
              正在读取设置状态…
            </div>
          )}
          <ol className="space-y-3">
            {steps.map((step, index) => (
              <li
                key={step.id}
                className={cn(
                  'flex items-start gap-3 rounded-xl border p-3',
                  step.status === 'current'
                    ? 'border-blue-200 bg-blue-50/50'
                    : 'border-slate-200 bg-white',
                )}
              >
                <span className="mt-0.5 shrink-0">
                  {step.done ? (
                    <CheckCircle2 size={20} className="text-emerald-500" />
                  ) : step.status === 'current' ? (
                    <span className="flex size-5 items-center justify-center rounded-full bg-blue-600 text-[11px] font-semibold text-white">
                      {index + 1}
                    </span>
                  ) : (
                    <Circle size={20} className="text-slate-300" />
                  )}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <p
                      className={cn(
                        'text-sm font-medium',
                        step.done ? 'text-slate-500 line-through' : 'text-slate-900',
                      )}
                    >
                      {step.title}
                    </p>
                    {step.status === 'current' && (
                      <span className="rounded-full bg-blue-100 px-2 py-0.5 text-[10px] font-medium text-blue-700">
                        进行中
                      </span>
                    )}
                    {step.done && (
                      <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-medium text-emerald-700">
                        已完成
                      </span>
                    )}
                  </div>
                  <p className="mt-0.5 text-xs text-slate-500">{step.description}</p>
                  {!step.done && (
                    <Link
                      to={step.href}
                      onClick={onClose}
                      className={cn(
                        'mt-2 inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium',
                        step.status === 'current'
                          ? 'bg-blue-600 text-white hover:bg-blue-700'
                          : 'border border-slate-200 bg-white text-slate-700 hover:bg-slate-50',
                      )}
                    >
                      {step.actionLabel}
                      <ArrowRight size={13} />
                    </Link>
                  )}
                </div>
              </li>
            ))}
          </ol>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3">
          {complete ? (
            <button
              type="button"
              onClick={onClose}
              className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-700"
            >
              <CheckCircle2 size={15} />
              完成
            </button>
          ) : (
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              稍后再说
            </button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export default OnboardingWizard;
