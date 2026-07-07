# AdPilot AI

**An AI-driven, multi-store Amazon Ads hosting & cross-border e-commerce operations platform.**

AdPilot AI combines goal-based advertising automation, a team-oriented cross-border ERP, and an internal AI-agent approval/execution workflow into a single operations console. It is built around **Goal-Based Ads Optimization**: operators set business goals, and the system proposes and (within safety boundaries) executes bid, budget, keyword, and negative-keyword changes across connected stores.

> Positioning: *Perpetua-style ads automation* + *team ERP* + *internal AI-agent approval/execution*.

---

## Highlights

- **Goal-based ad optimization** — bid / budget / keyword / negative-keyword recommendations driven by target ACoS and campaign goals.
- **Safety-bounded AI hosting** — every automated change is clamped to a resolved, most-restrictive-wins *Safety Boundary* (campaign → goal → store → org → system). Includes a **kill switch**, **shadow mode**, learning periods, idempotency, optimistic locking, an outbox pattern, HMAC-verified platform callbacks, and pre-submission revalidation.
- **Multi-store, multi-platform** — Amazon Ads (OAuth via Login with Amazon), plus independent-site and TikTok workspaces; data sync scaffolding for Shopify / WooCommerce / TikTok Shop.
- **Full RBAC** — org-scoped users, roles, permissions, menu/button/data-scope gating, and store-group data scoping.
- **Cross-border ERP modules** — orders, returns, refunds, settlements, procurement, suppliers, warehouses, logistics, finance, customer service, and reviews.
- **AI everywhere** — Insight Agent (natural-language analytics), Listing AI, smart diagnosis, and configurable OpenAI-compatible model settings.
- **Audit & governance** — audit logging on sensitive mutations, rollback support, and an operational `/api/health` readiness endpoint.
- **Tested** — extensive unit and **property-based** test suites on both backend (JUnit + jqwik) and frontend (Vitest + fast-check).

---

## Current implementation status

- **Frontend:** 65 configured route paths across the SPA. Treat the product as a workflow-driven operations app, not a page-count checklist.
- **Backend:** 40 module directories (~1,100+ Java files) under `backend-java/src/main/java/com/adpilot`.
- **Database:** `backend-java/db/schema.sql` is the single source of truth. Standalone indexes and later-version columns are applied through idempotent helpers (`adpilot_create_index_if_missing(...)`, `adpilot_add_column_if_missing(...)`), so re-importing the schema safely reconciles an existing database instead of failing on duplicates.
- **Production security:** `application-prod.yml` requires `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` — it never falls back to development credentials. The JWT signing key must be ≥ 32 bytes (HS256); default token expiry is 2 hours.
- **Session security:** token revocation is configurable via `adpilot.security.session.revocation-fail-closed` — production defaults to fail-closed, development to fail-open for local availability.
- **Hosting governance:** kill switch and shadow-mode enforcement are implemented. Canary rollout, SLO monitoring, and drift monitoring are explicit, disabled-by-default capabilities pending end-to-end persistence, metrics, alerting, and UI.
- **External integrations:** connector flows expose explicit states (`not_authorized`, `syncing`, `failed`, `last_success_at`); the UI never shows fake success when a credential or backend endpoint is missing.

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, React Router, TanStack Query |
| Backend | Java 17, Spring Boot 3.x, MyBatis-Plus, Spring Security (JWT) |
| Database | MySQL 8.0 (authoritative; schema imported manually) |
| Cache / sessions | Redis |
| AI | Pluggable OpenAI-compatible Chat Completions client |
| Testing | JUnit 5 + jqwik (backend), Vitest + fast-check (frontend) |

Schema ownership: **Flyway is disabled and Hibernate `ddl-auto` is `none`.** The database is decoupled from the app and provisioned by importing `backend-java/db/schema.sql`.

---

## Project structure

```
.
├── frontend/                     # React + TypeScript + Vite SPA
│   ├── src/app/
│   │   ├── pages/                # Route pages
│   │   ├── components/           # Shared UI (incl. shadcn/ui in components/ui)
│   │   ├── lib/                  # API client, auth, hooks, utilities
│   │   └── i18n/                 # Localization catalog
│   └── vite.config.ts
├── backend-java/                 # Spring Boot backend (primary)
│   ├── src/main/java/com/adpilot/
│   │   ├── common/               # Cross-cutting: security, config, utils, exceptions
│   │   └── modules/              # Feature modules (auth, user, advertising, order, ...)
│   ├── src/main/resources/       # application*.yml, MyBatis mappers, i18n
│   └── db/schema.sql             # ← single authoritative MySQL 8.0 schema
├── deploy/                       # nginx.conf + deploy.sh (reference)
└── README.md
```

### Backend modules (selected)

`auth`, `user`, `store`, `product`, `dashboard`, `task`, `audit`, `report`, `advertising` (incl. AI hosting engine), `keyword`, `listing`, `upload`, `importcenter`, `dataquality`, `profit`, `inventory`, `approval`, `automation`, `rollback`, `feishu`, `order`, `returnorder`, `refund`, `settlement`, `procurement`, `supplier`, `warehouse`, `logistics`, `finance`, `customer`, `review`, `listingops`, `apisync`, `organization`.

---

## Getting started

### Prerequisites

- Java 17+
- Node.js 18+ (with `pnpm` or `npm`)
- MySQL 8.0
- Redis (optional locally; the app degrades gracefully to in-memory fallbacks)

### 1. Database

```bash
# Create an empty utf8mb4 database, then import the authoritative schema.
mysql -u <user> -p -e "CREATE DATABASE adpilot CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u <user> -p adpilot < backend-java/db/schema.sql
```

`schema.sql` is idempotent and safe to re-run: it creates missing tables/indexes/columns without touching existing data.

### 2. Backend

```bash
cd backend-java

# Local dev credentials are read from environment variables (never committed):
export DB_USERNAME=youruser DB_PASSWORD=yourpass
export JWT_SECRET="a-strong-random-secret-at-least-32-bytes-long"

./mvnw spring-boot:run            # starts on http://localhost:8090
```

### 3. Frontend

```bash
cd frontend
npm install
npm run dev                       # starts on http://localhost:5173, proxies /api → :8090
```

Build for production: `npm run build` (outputs `frontend/dist/`).

---

## Configuration (environment variables)

No secrets are committed. Provide these via environment variables:

| Variable | Purpose |
|----------|---------|
| `DB_USERNAME` / `DB_PASSWORD` | MySQL credentials |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | MySQL connection (defaults: `localhost` / `3306` / `adpilot`) |
| `JWT_SECRET` | JWT signing key — **required in prod**, must be ≥ 32 bytes |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis connection |
| `CORS_ORIGINS` | Comma-separated allowed frontend origins |
| `ADPILOT_CRYPTO_SECRET` | AES key derivation for encrypting third-party credentials at rest |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | Feishu/Lark integration (optional) |
| `ADPILOT_AMAZON_ADS_CLIENT_ID` / `_SECRET` / `_REDIRECT_URI` | Amazon Ads Login-with-Amazon OAuth (optional) |

The `prod` profile fails fast on startup if `JWT_SECRET` is missing, blank, the dev default, or shorter than 32 bytes.

---

## Testing

```bash
# Backend (unit + property-based tests)
cd backend-java && ./mvnw test

# Frontend (Vitest + fast-check)
cd frontend && npm run typecheck && npm test
```

---

## Deployment

A reference reverse-proxy setup lives in `deploy/nginx.conf` and `deploy/deploy.sh`. Typical flow:

1. Build the backend jar: `./mvnw clean package` → run with `--spring.profiles.active=prod`.
2. Build the frontend: `npm run build` → serve `frontend/dist/` behind Nginx.
3. Reverse-proxy `/api` from the frontend host to the backend (same-origin, avoids CORS), or set `VITE_API_BASE_URL` to the backend origin.

**Before going live**, rotate all secrets from their development defaults (`JWT_SECRET`, DB password, `ADPILOT_CRYPTO_SECRET`, integration credentials).

---

## Security notes

- Secrets are never committed; all production credentials come from environment variables.
- RBAC is enforced server-side; cross-tenant access is scoped via a data-scope service.
- Sensitive mutations are written to an audit log; no credential values are ever logged.
- AI-driven ad-spend changes are always clamped to resolved safety boundaries and can be halted instantly via the kill switch.

---

## License

Released under the MIT License. See [LICENSE](LICENSE).
