// Advertising-list AI人格 column cell (Req 50.7 / 50.8 / 50.9).
//
// Shows the Campaign's RESOLVED AI_Personality as a compact label that does not
// rely on color alone (a shape icon per personality), with an indicator
// distinguishing an inherited value (from the Goal/Store default) from a
// Campaign-level override. Clicking the cell asks the parent to open the
// personality-rules drawer (Req 50.9); the cell never edits inline.

import { cn } from '../../lib/utils';
import {
  PERSONALITY_META,
  inheritanceLabel,
  isOverride,
  resolveCampaignPersonality,
  type ResolvePersonalityInput,
} from '../../lib/aiPersonality';
import { PersonalityIcon } from './PersonalityIcon';

export function ResolvedPersonalityCell({
  campaignPersonality,
  goalPersonality,
  storePersonality,
  onOpen,
  className,
}: ResolvePersonalityInput & {
  /** Invoked when the operator clicks the cell to view the full personality rules. */
  onOpen?: () => void;
  className?: string;
}) {
  const { personality, source } = resolveCampaignPersonality({
    campaignPersonality,
    goalPersonality,
    storePersonality,
  });
  const meta = PERSONALITY_META[personality];
  const override = isOverride(source);
  const sourceLabel = inheritanceLabel(source);

  const content = (
    <>
      <span
        className={cn(
          'inline-flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px] font-semibold',
          meta.chipClass,
        )}
      >
        <PersonalityIcon personality={personality} size={12} />
        {meta.display}
      </span>
      <span
        className={cn(
          'text-[10px] font-medium',
          override ? 'text-amber-600' : 'text-slate-400',
        )}
        title={sourceLabel}
      >
        {override ? '● 覆盖' : '○ 继承'}
      </span>
    </>
  );

  if (!onOpen) {
    return <span className={cn('inline-flex items-center gap-1.5', className)}>{content}</span>;
  }

  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={`查看 ${meta.display} 人格规则（${sourceLabel}）`}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md px-1 py-0.5 hover:bg-slate-50 transition-colors',
        className,
      )}
    >
      {content}
    </button>
  );
}

export default ResolvedPersonalityCell;
