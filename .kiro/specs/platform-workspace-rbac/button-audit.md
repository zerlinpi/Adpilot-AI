# Button Audit — platform-workspace-rbac (Task 15.3)

_Requirements: 17.1, 17.2, 17.3, 17.5 (and 17.4, surfaced via the shared API client / per-page feedback)._

This is the enumerated record of the page-by-page audit of every interactive
control reachable through the four Nav_Blocks (亚马逊, 独立站, 物流, 财务统计)
and the Account_Area (系统设置, 飞书机器人, 审计回滚, CSV导入, plus the preserved
administration pages), per `navConfig.ts` / `Layout.tsx` and `routes.tsx`.

## Method

- Enumerated controls from `navConfig.ts` → `routes.tsx` page components.
- Classified a control as **broken** when it (Req 17.3): invokes a missing/incorrect/
  unhandled endpoint, produces no response on activation (no `onClick`), or fails
  silently (no success/error feedback on a failed backend request, Req 17.4).
- Repaired each broken control to invoke the correct endpoint / perform a real
  action and surface success/error feedback using the existing per-page
  feedback-banner convention (`actionMessage` + toast banner, as used by
  `SearchTermsPage`, `KeywordsPage`, `ListingAIPage`, etc.).

## Coverage summary

The vast majority of controls across the navigation tree are correctly wired:
they call the shared API client (`frontend/src/app/lib/api.ts`, which already
surfaces every error per task 15.1) and render loading/error/feedback states.
No `TODO`/`FIXME` stub handlers, no empty `onClick={() => {}}` handlers, and no
silent `catch {}` blocks were found. The broken controls below were the
exceptions found and repaired.

## Broken controls found and repaired

| # | Page (route) | Control | Observed failure | Repair |
|---|---|---|---|---|
| 1 | 报表中心 `/reports` (`ReportsPage.tsx`) | **生成报告** button | On a failed `POST /api/reports/generate`, the error was only `console.error`'d — the button looked like it did nothing (violates 17.4). | Surface the error in an action feedback banner; show a success banner on generate. |
| 2 | 报表中心 `/reports` (`ReportsPage.tsx`) | **PDF** download button (per report) | No `onClick` handler at all — dead control; no backend export endpoint exists. | Wired to a client-side print-to-PDF view (`window.open` + print) built from the report fields; success/error feedback. |
| 3 | 报表中心 `/reports` (`ReportsPage.tsx`) | **CSV** download button (per report) | No `onClick` handler at all — dead control. | Wired to a real client-side CSV download (BOM + escaped fields) of the report; success/error feedback. |
| 4 | AI优化建议 `/recommendations` (`RecommendationsPage.tsx`) | **应用 / 忽略 / 关注** action buttons | On a failed apply/dismiss/watch request the failure was only `console.error`'d; the row silently stayed unchanged (violates 17.4). | Added an `actionMessage` feedback banner; each handler now reports success or the backend error message. |
| 5 | AI优化建议 `/recommendations` (`RecommendationsPage.tsx`) | **应用全部低风险** button | Per-item failures were swallowed via `console.error`; no aggregate feedback. | Now counts failures and reports either an aggregate success or a "N 条失败" error banner. |
| 6 | SKU利润 `/product-profit` (`ProductProfitPage.tsx`) | **导出 CSV** button | No `onClick` handler at all — dead control. | Wired to a real client-side CSV export of the filtered SKU-profit rows; success/error feedback (incl. an "暂无可导出的数据" guard). |

## Notes / observed-but-out-of-scope

- The Google Ads block items (`/google-ads/*`) and a few not-yet-built routes in
  `navConfig.ts` are intentionally **not rendered** because their routes do not
  resolve in `RESOLVABLE_ROUTES` (Req 2.5 by design). They are not "broken
  controls" — they are simply hidden until their UI tasks (14.1) add the routes.
- The global header **search input** and **notification bell** in `Layout.tsx`
  are shell chrome (not Nav_Block / Account_Area action controls) and are owned
  by the navigation task (13.1); they were left unchanged to avoid scope creep.

## Verification

- `npx tsc --noEmit`: the three edited files (`ReportsPage.tsx`,
  `RecommendationsPage.tsx`, `ProductProfitPage.tsx`) produce **no diagnostics**.
  (Pre-existing, unrelated type errors elsewhere in the project — lucide icon
  typing, `main.tsx` import extension, etc. — were present before this task and
  are not introduced by it.)
- `npm run build` (`vite build`): **succeeds** (exit 0, 2532 modules transformed).
