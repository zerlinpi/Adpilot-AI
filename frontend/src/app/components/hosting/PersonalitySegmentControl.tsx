// Three-segment AI人格 single-select control (Req 50.3) with the 人格影响预览.
//
// Renders 常规型 / 平衡型 / 激进型 as a segmented single-select (NOT a dropdown,
// Req 50.3), each segment showing its icon, display name, and tone. Selecting a
// segment surfaces the "人格影响预览" — to the RIGHT on a desktop viewport and
// BELOW the selector on a small (mobile) screen (Req 50.5). Choosing the
// `aggressive` personality additionally shows a risk warning (Req 49.17).

import { AlertTriangle } from 'lucide-react';

import { cn } from '../../lib/utils';
import {
  AI_PERSONALITY_VALUES,
  PERSONALITY_META,
  type AiPersonality,
  type PersonalityPolicy,
  type PersonalityPolicyMap,
} from '../../lib/aiPersonality';
import { PersonalityIcon } from './PersonalityIcon';
import { PersonalityImpactPreview } from './PersonalityImpactPreview';

export function PersonalitySegmentControl({
  value,
  onChange,
  policies,
  /**
   * Layout for the preview: `side` places it to the right on ≥sm viewports and
   * stacks below on small screens (Req 50.5); `below` always stacks (used when
   * the drawer is already a full-screen mobile panel with no right side).
   */
  previewLayout = 'side',
  disabled = false,
  className,
}: {
  value: AiPersonality;
  onChange: (next: AiPersonality) => void;
  policies?: PersonalityPolicyMap;
  previewLayout?: 'side' | 'below';
  disabled?: boolean;
  className?: string;
}) {
  const selectedPolicy: PersonalityPolicy | undefined = policies?.[value];

  const segments = (
    <div role="radiogroup" aria-label="AI人格" className="grid grid-cols-3 gap-2">
      {AI_PERSONALITY_VALUES.map((personality) => {
        const meta = PERSONALITY_META[personality];
        const selected = personality === value;
        return (
          <button
            key={personality}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={disabled}
            onClick={() => onChange(personality)}
            className={cn(
              'flex flex-col items-center gap-1 rounded-xl border-2 px-2 py-3 text-center transition-all',
              selected ? meta.accentClass : 'border-slate-200 text-slate-600 hover:border-slate-300',
              disabled && 'cursor-not-allowed opacity-60',
            )}
          >
            <PersonalityIcon personality={personality} size={18} />
            <span className="text-sm font-semibold">{meta.display}</span>
            <span className="text-[11px] leading-tight text-slate-400">{meta.tagline}</span>
          </button>
        );
      })}
    </div>
  );

  const preview = <PersonalityImpactPreview personality={value} policy={selectedPolicy} />;

  return (
    <div className={cn('space-y-3', className)}>
      {previewLayout === 'side' ? (
        <div className="grid gap-4 md:grid-cols-2">
          <div className="space-y-3">{segments}</div>
          {preview}
        </div>
      ) : (
        <div className="space-y-3">
          {segments}
          {preview}
        </div>
      )}

      {value === 'aggressive' && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-amber-800">
          <AlertTriangle size={15} className="mt-0.5 shrink-0" />
          <p className="text-xs leading-relaxed">
            激进型人格会以更大幅度、更高频率调整竞价与预算，并提高自动化程度，可能带来更高的 ACoS 与花费。请确认已设置合理的安全边界。
          </p>
        </div>
      )}
    </div>
  );
}

export default PersonalitySegmentControl;
