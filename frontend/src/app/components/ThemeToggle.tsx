import { useEffect, useState } from 'react';
import { useTheme } from 'next-themes';
import { Sun, Moon } from 'lucide-react';

const LABEL = '切换深色模式';

/** Header theme switch: shows Moon in light mode (click → dark) and Sun in dark
 *  mode (click → light). Colors come exclusively from semantic tokens, focus is
 *  keyboard-visible (focus-visible:ring), and the control is fully labelled for
 *  assistive tech (aria-label + title). Until the component mounts on the client
 *  we render a same-size, aria-hidden placeholder so the icon never flips between
 *  the server/first paint and hydration. */
export function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  const buttonClass =
    'inline-flex items-center justify-center p-2 rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none';

  // Pre-mount placeholder — identical footprint (same class + icon size), hidden
  // from the accessibility tree, so there is no SSR/first-paint flash or icon
  // mismatch during hydration.
  if (!mounted) {
    return (
      <div className={buttonClass} aria-hidden="true">
        <Moon size={16} />
      </div>
    );
  }

  const isDark = theme === 'dark';

  return (
    <button
      type="button"
      onClick={() => setTheme(isDark ? 'light' : 'dark')}
      aria-label={LABEL}
      title={LABEL}
      className={buttonClass}
    >
      {isDark ? <Sun size={16} /> : <Moon size={16} />}
    </button>
  );
}

export default ThemeToggle;
