import { RouterProvider } from "react-router";
import { QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { router } from "./routes";
import { AppErrorBoundary } from "./components/AppErrorBoundary";
import { Toaster } from "./components/ui/sonner";
import { queryClient } from "./lib/queryClient";

export default function App() {
  return (
    // ThemeProvider is mounted (pinned to light for now) so the theming token
    // plumbing + sonner's theme-aware Toaster have a context; a dark-mode toggle
    // can be enabled later without re-wiring. enableSystem is off so the app
    // stays light until an explicit theme switch is introduced.
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
      <AppErrorBoundary>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
          {/* Global, app-wide toast notifications (unified feedback channel). */}
          <Toaster richColors closeButton position="top-right" />
        </QueryClientProvider>
      </AppErrorBoundary>
    </ThemeProvider>
  );
}
