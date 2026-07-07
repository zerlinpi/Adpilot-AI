// 人格影响预览 (personality impact preview) — Req 50.4 / 50.5.
//
// Lists the concrete allowed actions for the selected AI_Personality: core goal,
// expected action frequency, maximum adjustment magnitude, auto keyword/negative
// behavior, and the approval requirement. Every numeric figure is derived from
// the backend-supplied Personality_Policy (Req 49.6); when no policy is loaded
// the magnitude/frequency rows show an explicit loading note instead of a made-up
// value.

import { Loader2 } from 'lucide-react';

import { cn } from '../../lib/utils';
import {
  derivePersonalityPreview,
  PERSONALITY_META,
  type AiPersonality,
  type PersonalityPolicy,
} from '../../lib/aiPersonality';
import { PersonalityIcon } from './PersonalityIcon';

export function PersonalityImpactPreview({
  personality,
  policy,
  className,
}: {
  personality: AiPersonality;
  policy?: PersonalityPolicy | null;
  className?: string;
}) {
  const meta = PERSONALITY_META[personality];
  const items = derivePersonalityPreview(personality, policy);

  return (
    <section
      aria-label="人格影响预览"
      className={cn('rounded-xl border border-slate-200 bg-slate-50/60 p-4', className)}
    >
      <header className="flex items-center gap-2 mb-3">
        <span
          className={cn(
            'inline-flex h-7 w-7 items-center justify-center rounded-lg border',
            meta.accentClass,
          )}
        >
          <PersonalityIcon personality={personality} size={15} />
        </span>
        <div>
          <p className="text-sm font-semibold text-slate-900">人格影响预览 · {meta.display}</p>
          <p className="text-xs text-slate-500">{meta.tagline}</p>
        </div>
      </header>

      <dl className="space-y-2.5">
        {items.map((item) => (
          <div key={item.key} className="flex flex-col gap-0.5">
            <dt className="text-xs font-medium text-slate-500">{item.label}</dt>
            <dd
              className={cn(
                'text-sm',
                item.pending ? 'text-slate-400 inline-flex items-center gap-1' : 'text-slate-800',
              )}
            >
              {item.pending && <Loader2 size={12} className="animate-spin" />}
              {item.value}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export default PersonalityImpactPreview;
