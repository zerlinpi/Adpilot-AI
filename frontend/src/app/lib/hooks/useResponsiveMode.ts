// AdPilot AI — useResponsiveMode
// (advertising-workspace-rework Req 43.2, 43.3, 43.4)
//
// A thin React hook over the pure {@link resolveResponsiveMode} decision: it
// observes the viewport width and re-resolves the advertising responsive mode
// whenever the 768px threshold is crossed (Req 43.4). All of the actual policy
// lives in `../advertisingResponsive`; this hook only supplies the live width.
//
// It is SSR/test safe: when `window` is unavailable it falls back to the
// desktop mode (the full experience), and it cleans up its listener on unmount.

import { useEffect, useState } from 'react';

import {
  resolveResponsiveMode,
  type ResponsiveMode,
} from '../advertisingResponsive';

/** Read the current viewport width, defaulting to a desktop width off-DOM. */
function currentWidth(): number {
  if (typeof window === 'undefined') return Number.POSITIVE_INFINITY;
  return window.innerWidth;
}

/**
 * Returns the live advertising {@link ResponsiveMode} for the current viewport
 * width, updating as the operator resizes across the 768px threshold.
 */
export function useResponsiveMode(): ResponsiveMode {
  const [mode, setMode] = useState<ResponsiveMode>(() =>
    resolveResponsiveMode(currentWidth()),
  );

  useEffect(() => {
    if (typeof window === 'undefined') return;

    const update = () => setMode(resolveResponsiveMode(window.innerWidth));
    // Re-sync once on mount in case the width changed before the effect ran.
    update();

    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, []);

  return mode;
}
