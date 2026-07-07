// Shared AI_Personality icon — a SHAPE per personality so the AI人格 surfaces are
// legible without relying on color alone (Req 50.8): shield = conservative,
// scale = balanced, trend = aggressive.

import { Shield, Scale, TrendingUp, type LucideIcon } from 'lucide-react';

import type { AiPersonality, PersonalityIconKind } from '../../lib/aiPersonality';
import { PERSONALITY_ICON } from '../../lib/aiPersonality';

const ICONS: Record<PersonalityIconKind, LucideIcon> = {
  shield: Shield,
  scale: Scale,
  trend: TrendingUp,
};

export function PersonalityIcon({
  personality,
  size = 14,
  className,
}: {
  personality: AiPersonality;
  size?: number;
  className?: string;
}) {
  const Icon = ICONS[PERSONALITY_ICON[personality]];
  return <Icon size={size} className={className} aria-hidden />;
}

export default PersonalityIcon;
