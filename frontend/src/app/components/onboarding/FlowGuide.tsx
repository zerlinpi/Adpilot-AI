// AdPilot AI — 后台内分步操作指引 (multistore-ai-ads-operations, Req 8.3/8.4/8.6)
//
// A small, reusable, collapsible "step-by-step guidance" panel dropped into the
// key operational pages (store connection, report sync & product-ad data,
// single-product ad creation, independent-site write-back, TikTok connection,
// Feishu sending). It renders ordered steps in business language and can:
//   - annotate any step as "暂不支持" (unsupported) so capabilities are labeled
//     honestly rather than offering silently-failing entries (Req 8.6);
//   - render an extra explanatory note block (e.g. the Amazon T+1 / preliminary
//     data explanation, kept consistent with ProductAdDataPage, Req 8.4).
//
// The panel is purely presentational and self-contained; its collapse state is
// remembered per `storageKey` in localStorage so a returning user keeps it
// closed once read.

import { useCallback, useState, type ReactNode } from 'react';
import { Ban, ChevronDown, HelpCircle } from 'lucide-react';
import { cn } from '../../lib/utils';

/** A single ordered step in a guided flow. */
export interface FlowGuideStep {
  /** Short imperative title, e.g. "连接店铺". */
  title: string;
  /** Optional longer explanation in business language. */
  detail?: ReactNode;
  /** When true the step is rendered with a "暂不支持" badge (Req 8.6). */
  unsupported?: boolean;
}

export interface FlowGuideProps {
  /** Panel heading, e.g. "操作指引：单产品广告创建". */
  title: string;
  /** Optional one-line intro shown under the heading. */
  intro?: ReactNode;
  /** Ordered steps to walk the user through. */
  steps: FlowGuideStep[];
  /** Optional explanatory note rendered below the steps (e.g. T+1 说明). */
  note?: ReactNode;
  /** Whether the panel starts expanded when no stored preference exists. */
  defaultOpen?: boolean;
  /** localStorage key used to remember the collapsed/expanded preference. */
  storageKey?: string;
  /** Extra classes for the outer container. */
  className?: string;
}

function readStoredOpen(storageKey: string | undefined, fallback: boolean): boolean {
  if (!storageKey || typeof window === 'undefined') return fallback;
  try {
    const raw = window.localStorage.getItem(`flowguide:${storageKey}`);
    if (raw === 'open') return true;
    if (raw === 'closed') return false;
  } catch {
    /* ignore storage failures — fall back to default */
  }
  return fallback;
}

function writeStoredOpen(storageKey: string | undefined, open: boolean): void {
  if (!storageKey || typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(`flowguide:${storageKey}`, open ? 'open' : 'closed');
  } catch {
    /* ignore storage failures */
  }
}

/**
 * Collapsible step-by-step guidance panel. Renders an ordered list of steps,
 * each optionally annotated as "暂不支持", plus an optional explanatory note.
 */
export function FlowGuide({
  title,
  intro,
  steps,
  note,
  defaultOpen = false,
  storageKey,
  className,
}: FlowGuideProps) {
  const [open, setOpen] = useState(() => readStoredOpen(storageKey, defaultOpen));

  const toggle = useCallback(() => {
    setOpen((prev) => {
      const next = !prev;
      writeStoredOpen(storageKey, next);
      return next;
    });
  }, [storageKey]);

  const contentId = storageKey ? `flowguide-${storageKey}` : undefined;

  return (
    <div
      className={cn(
        'rounded-xl border border-blue-100 bg-blue-50/50 overflow-hidden',
        className,
      )}
    >
      <button
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls={contentId}
        className="flex w-full items-center gap-2 px-4 py-3 text-left"
      >
        <HelpCircle size={16} className="text-blue-500 flex-shrink-0" />
        <span className="flex-1 min-w-0">
          <span className="block text-sm font-medium text-slate-700">{title}</span>
          {intro && <span className="block text-xs text-slate-500 mt-0.5">{intro}</span>}
        </span>
        <ChevronDown
          size={16}
          className={cn(
            'text-slate-400 flex-shrink-0 transition-transform',
            open && 'rotate-180',
          )}
        />
      </button>

      {open && (
        <div id={contentId} className="px-4 pb-4 pt-0">
          <ol className="space-y-2.5">
            {steps.map((step, i) => (
              <li key={i} className="flex gap-3">
                <span className="mt-0.5 flex size-5 flex-shrink-0 items-center justify-center rounded-full bg-blue-600 text-[11px] font-semibold text-white">
                  {i + 1}
                </span>
                <div className="min-w-0 flex-1 text-sm">
                  <p className="font-medium text-slate-700 inline-flex flex-wrap items-center gap-1.5">
                    {step.title}
                    {step.unsupported && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border bg-slate-100 text-slate-400 border-slate-200">
                        <Ban size={11} />
                        暂不支持
                      </span>
                    )}
                  </p>
                  {step.detail && (
                    <p className="mt-0.5 text-xs text-slate-500 leading-relaxed">{step.detail}</p>
                  )}
                </div>
              </li>
            ))}
          </ol>
          {note && (
            <div className="mt-3 rounded-lg border border-blue-100 bg-white/70 px-3 py-2 text-xs text-slate-500 leading-relaxed">
              {note}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default FlowGuide;
