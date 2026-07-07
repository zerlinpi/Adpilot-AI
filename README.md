# AdPilot AI 鈥?瀹屾暣椤圭洰鏂囨。

## Current Implementation Status (2026-06-18)

- Frontend: 65 configured route paths and 93 page `.tsx` files. Treat the product as a workflow-driven operations app, not as a page-count checklist.
- Backend: 40 module directories and 1135 Java files under `backend-java/src/main/java/com/adpilot`.
- Database: `backend-java/db/schema.sql` remains the single source of truth. Standalone indexes are created through `adpilot_create_index_if_missing(...)` so repeat imports skip existing indexes instead of failing on duplicate index names.
- Production security: `application-prod.yml` requires `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET`; it no longer falls back to development credentials. JWT default expiry is 2 hours (`7200000` ms).
- Redis/session security: token revocation checks are configurable with `adpilot.security.session.revocation-fail-closed`; production defaults to fail-closed, development defaults to fail-open for local availability.
- Hosting governance: kill switch and shadow-mode enforcement are implemented. Canary rollout, SLO monitoring, and drift monitoring are explicit disabled capabilities until persistence, metrics, alerting, and UI controls are implemented end to end.
- External integrations: connector flows should expose explicit states such as `not_authorized`, `syncing`, `failed`, and `last_success_at`; UI actions must not show fake success when a platform credential or backend endpoint is missing.

## 涓€銆侀」鐩杩?
**AdPilot AI** 鏄叕鍙稿唴閮ㄤ娇鐢ㄧ殑璺ㄥ鐢靛晢缁忚惀绯荤粺锛屽熀浜?鐩爣椹卞姩骞垮憡浼樺寲"锛圙oal-Based Ads Optimization锛夌殑浜у搧閫昏緫鏋勫缓銆?
**浜у搧瀹氫綅**锛欰dPilot AI = Perpetua 骞垮憡鑷姩鍖?+ 棰嗘槦寮忚法澧?ERP + 鍏徃鍐呴儴 AI Agent 瀹℃壒鎵ц绯荤粺

**鐩爣鐢ㄦ埛**锛氬叕鍙稿唴閮ㄨ繍钀ャ€佸箍鍛婃姇鎵嬨€侀噰璐€佷粨搴撱€佽储鍔°€佸鏈嶃€佷骇鍝併€佽璁°€佺鐞嗗眰銆?
**鎶€鏈灦鏋?*锛?- 鍓嶇锛歊eact + TypeScript + Vite + Tailwind CSS + shadcn/ui锛?*53 涓寮忚矾鐢遍〉闈?*锛?- 鍚庣锛圥RIMARY锛夛細Java 17 + Spring Boot 3.x + MyBatis Plus + MyBatis XML锛?*34 涓ā鍧楋紝403 涓枃浠?*锛?- 鏁版嵁搴擄細MySQL 8.0锛圫ource of Truth锛屽敮涓€鏉冨▉鏁版嵁搴擄級
- Schema锛?*鏁版嵁搴撲笌搴旂敤瑙ｈ€?* 鈥斺€?鍏ㄩ儴寤鸿〃涓庣瀛愭暟鎹泦涓湪鍗曚竴鏂囦欢 `backend-java/db/schema.sql`锛岀敱杩愮淮鎵嬪姩瀵煎叆锛涘簲鐢ㄥ惎鍔?*涓嶅仛杩佺Щ銆佷笉鑷姩鏀硅〃**锛坄flyway.enabled=false`銆乣ddl-auto=none`锛?- 缂撳瓨锛歊edis
- 闆嗘垚锛氶涔︽満鍣ㄤ汉锛堣嚜瀹氫箟 Webhook 鍗曞悜閫氱煡锛屾寜璐﹀彿/鎸夊簵閾虹嫭绔嬬粦瀹氾級銆丄mazon Ads OAuth 搴楅摵鎺堟潈銆佸钩鍙版暟鎹悓姝ワ紙Shopify / WooCommerce / TikTok Shop锛夈€丄I 鎺ュ彛锛堜换鎰?OpenAI 鍏煎澶фā鍨嬶級
- 澶氬簵閾哄鑸細鎸夊钩鍙版棌闅旂鐨勪簲鍧楅《灞傚鑸紙浜氶┈閫?/ 鐙珛绔?/ 鐗╂祦 / 璐㈠姟缁熻 / TikTok锛夛紝鍚勬笭閬撳簵閾哄彧鍦ㄦ湰娓犻亾鑿滃崟鍐呰繛鎺ヤ笌鏄剧ず锛屼簰涓嶄覆鍙?- 閬楃暀鍚庣锛歂ode.js + Express + Prisma锛坄legacy-backend/`锛屽凡鍋滅敤锛?
**褰撳墠鐘舵€?*锛?- 鉁?Phase A锛欽ava 鍚庣鏍稿績閾捐矾锛坅uth / user / store / product / dashboard / task / audit / report锛?- 鉁?Phase B锛歋QL 鏂囦欢浣撶郴钀藉湴锛?3 DDL + 14 DML + 13 Migration + 15 Report锛?- 鉁?Phase C锛欰dvertising + Keyword Intelligence + Listing AI + Product Upload
- 鉁?Phase D锛欳SV Import + Data Quality
- 鉁?Phase E锛歅rofit + Inventory + Replenishment
- 鉁?Phase F锛氶涔﹀鎵?+ Automation + AI Audit + Rollback
- 鉁?Phase G锛氶鏄熷紡 ERP 妯″潡 Java 瀹炵幇锛堣鍗?閲囪喘/浠撳簱/鐗╂祦/璐㈠姟/瀹㈡湇/Review锛?- 鉁?Phase H锛氬钩鍙?API 杩炴帴妗嗘灦 Java 瀹炵幇锛圓mazon Ads OAuth 鎺堟潈 / SP-API / Shopify / WooCommerce / TikTok Shop 鏁版嵁鍚屾锛?- 鉁?涓枃鍖?+ ERP UI 缁勪欢 + 鍓嶇绋冲畾鎬?- 鉁?V1.3锛氳处鍙锋潈闄愮郴缁燂紙鐧诲綍/澶氳处鍙?RBAC/鑿滃崟鏉冮檺/鎸夐挳鏉冮檺/鏁版嵁鏉冮檺/20 涓唴閮ㄨ处鍙凤級
- 鉁?V1.4锛氶涔︽満鍣ㄤ汉锛堣嚜瀹氫箟 Webhook 鍗曞悜閫氱煡锛孉I 璇婃柇/寮傚父鎻愰啋/鏃ユ姤鍛ㄦ姤锛? Insight Agent 鎸佷箙鍖?+ AI 鎺ュ彛杩愯鏃堕厤缃紙OpenAI 鍏煎锛?
---

## 浜屻€佺洰褰曠粨鏋?
```
e:\AWS\
鈹溾攢鈹€ frontend/                          # 鍓嶇 React SPA锛?3 涓寮忚矾鐢遍〉闈級
鈹?  鈹溾攢鈹€ src/app/
鈹?  鈹?  鈹溾攢鈹€ pages/                     # 53 涓寮忛〉闈?+ 2 涓潪姝ｅ紡缁勪欢
鈹?  鈹?  鈹溾攢鈹€ components/
鈹?  鈹?  鈹?  鈹溾攢鈹€ erp/                   # ERP 椋庢牸閫氱敤缁勪欢锛? 涓級
鈹?  鈹?  鈹?  鈹溾攢鈹€ Layout.tsx
鈹?  鈹?  鈹?  鈹溾攢鈹€ AppErrorBoundary.tsx
鈹?  鈹?  鈹?  鈹斺攢鈹€ ui/                    # shadcn/ui
鈹?  鈹?  鈹溾攢鈹€ lib/
鈹?  鈹?  鈹?  鈹溾攢鈹€ api.ts                 # API 瀹㈡埛绔紙60+ 鍑芥暟锛?鈹?  鈹?  鈹?  鈹溾攢鈹€ useStoreId.ts          # 鍏变韩 storeId hook
鈹?  鈹?  鈹?  鈹斺攢鈹€ utils.ts
鈹?  鈹?  鈹溾攢鈹€ i18n/                      # 涓枃鏂囨浣撶郴锛? 涓枃浠讹級
鈹?  鈹?  鈹溾攢鈹€ data/mock.ts               # Mock锛堜粎闄嶇骇鐢級
鈹?  鈹?  鈹斺攢鈹€ types/index.ts
鈹?  鈹溾攢鈹€ vite.config.ts                 # /api 浠ｇ悊 鈫?鍚庣锛坔ttps://api.your-domain.example锛?鈹?  鈹斺攢鈹€ package.json
鈹?鈹溾攢鈹€ backend-java/                      # 鈽?Java Spring Boot 鍚庣锛圥RIMARY锛?鈹?  鈹溾攢鈹€ pom.xml
鈹?  鈹溾攢鈹€ src/main/java/com/adpilot/
鈹?  鈹?  鈹溾攢鈹€ AdPilotApplication.java
鈹?  鈹?  鈹溾攢鈹€ common/                    # 20 涓€氱敤缁勪欢
鈹?  鈹?  鈹斺攢鈹€ modules/                   # 34 涓ā鍧楋紙403 涓枃浠讹級
鈹?  鈹?      鈹溾攢鈹€ auth/          (7)   鉁?Phase A
鈹?  鈹?      鈹溾攢鈹€ user/          (20)  鉁?Phase A
鈹?  鈹?      鈹溾攢鈹€ store/         (9)   鉁?Phase A
鈹?  鈹?      鈹溾攢鈹€ product/       (7)   鉁?Phase A
鈹?  鈹?      鈹溾攢鈹€ dashboard/     (6)   鉁?Phase A
鈹?  鈹?      鈹溾攢鈹€ task/          (7)   鉁?Phase A
鈹?  鈹?      鈹溾攢鈹€ audit/         (11)  鉁?Phase A + F
鈹?  鈹?      鈹溾攢鈹€ report/        (5)   鉁?Phase A
鈹?  鈹?      鈹溾攢鈹€ advertising/   (58)  鉁?Phase C
鈹?  鈹?      鈹溾攢鈹€ keyword/       (15)  鉁?Phase C
鈹?  鈹?      鈹溾攢鈹€ listing/       (11)  鉁?Phase C
鈹?  鈹?      鈹溾攢鈹€ upload/        (7)   鉁?Phase C
鈹?  鈹?      鈹溾攢鈹€ importcenter/  (15)  鉁?Phase D
鈹?  鈹?      鈹溾攢鈹€ dataquality/   (7)   鉁?Phase D
鈹?  鈹?      鈹溾攢鈹€ profit/        (8)   鉁?Phase E
鈹?  鈹?      鈹溾攢鈹€ inventory/     (12)  鉁?Phase E
鈹?  鈹?      鈹溾攢鈹€ approval/      (9)   鉁?Phase F
鈹?  鈹?      鈹溾攢鈹€ automation/    (14)  鉁?Phase F
鈹?  鈹?      鈹溾攢鈹€ rollback/      (6)   鉁?Phase F
鈹?  鈹?      鈹溾攢鈹€ feishu/        (13)  鉁?Phase F
鈹?  鈹?      鈹溾攢鈹€ order/         (17)  鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ returnorder/   (7)   鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ refund/        (7)   鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ settlement/    (9)   鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ procurement/   (13)  鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ supplier/      (7)   鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ warehouse/     (15)  鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ logistics/     (9)   鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ finance/       (16)  鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ customer/      (9)   鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ review/        (20)  鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ listingops/    (15)  鉁?Phase G
鈹?  鈹?      鈹溾攢鈹€ apisync/       (12)  鉁?Phase H
鈹?  鈹?      鈹斺攢鈹€ organization/  (5)   鉁?鈹?  鈹斺攢鈹€ src/main/resources/
鈹?      鈹溾攢鈹€ application.yml / application-dev.yml
鈹?      鈹斺攢鈹€ i18n/messages_zh_CN.properties
鈹?鈹溾攢鈹€ backend-java/
鈹?  鈹斺攢鈹€ db/
鈹?      鈹斺攢鈹€ schema.sql                 # 鈽?鍞竴鏉冨▉ SQL 婧愶紙鎵嬪姩瀵煎叆锛涘惈鍏ㄩ噺 DDL + 绉嶅瓙鏁版嵁锛屽箓绛夛級
鈹?鈹溾攢鈹€ bigdata/                           # 鏁版嵁瀵煎叆/瀵煎嚭涓庡瓧鍏哥礌鏉?鈹?  鈹溾攢鈹€ import-files/
鈹?  鈹溾攢鈹€ exports/
鈹?  鈹斺攢鈹€ docs/database-dictionary.md
鈹?鈹溾攢鈹€ legacy-backend/                    # 鈿?閬楃暀 Node Express锛堝凡鍋滅敤锛?鈹溾攢鈹€ database/                          # Prisma Schema锛堝巻鍙插弬鑰冿級
鈹溾攢鈹€ docs/
鈹斺攢鈹€ README.md
```

---

## 涓夈€丣ava 鍚庣妯″潡锛?4 涓洰褰曪紝33 涓椿璺冩ā鍧楋紝403 涓枃浠讹級

| 妯″潡 | 鏂囦欢鏁?| Controller | Service | Entity | Mapper | Phase |
|------|--------|-----------|---------|--------|--------|-------|
| auth | 7 | 鉁?| 鉁?| - | - | A |
| user | 20 | 鉁?| 鉁?| 鉁?| 鉁?| A |
| store | 9 | 鉁?| 鉁?| 鉁?| 鉁?| A |
| product | 7 | 鉁?| 鉁?| 鉁?| 鉁?| A |
| dashboard | 6 | 鉁?| 鉁?| - | 鉁?| A |
| task | 7 | 鉁?| 鉁?| 鉁?| 鉁?| A |
| audit | 11 | 鉁?| 鉁?| 鉁?| 鉁?| A + F |
| report | 5 | 鉁?| 鉁?| 鉁?| 鉁?| A |
| advertising | 58 | 鉁?| 鉁?| 鉁?| 鉁?| C |
| keyword | 15 | 鉁?| 鉁?| 鉁?| 鉁?| C |
| listing | 11 | 鉁?| 鉁?| 鉁?| 鉁?| C |
| upload | 7 | 鉁?| 鉁?| 鉁?| 鉁?| C |
| importcenter | 15 | 鉁?| 鉁?| 鉁?| 鉁?| D |
| dataquality | 7 | 鉁?| 鉁?| 鉁?| 鉁?| D |
| profit | 8 | 鉁?| 鉁?| 鉁?| 鉁?| E |
| inventory | 12 | 鉁?| 鉁?| 鉁?| 鉁?| E |
| approval | 9 | 鉁?| 鉁?| 鉁?| 鉁?| F |
| automation | 14 | 鉁?| 鉁?| 鉁?| 鉁?| F |
| rollback | 6 | 鉁?| 鉁?| 鉁?| 鉁?| F |
| feishu | 13 | 鉁?| 鉁?| 鉁?| 鉁?| F |
| order | 17 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| returnorder | 7 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| refund | 7 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| settlement | 9 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| procurement | 13 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| supplier | 7 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| warehouse | 15 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| logistics | 9 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| finance | 16 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| customer | 9 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| review | 20 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| listingops | 15 | 鉁?| 鉁?| 鉁?| 鉁?| G |
| apisync | 12 | 鉁?| 鉁?| 鉁?| 鉁?| H |
| organization | 0 | 鈿狅笍 | 鈿狅笍 | 鈿狅笍 | 鈿狅笍 | A锛堢洰褰曚负绌猴紝寰呭疄鐜帮級 |

---

## 鍥涖€丒RP 閫氱敤缁勪欢锛? 涓級

| 缁勪欢 | 璇存槑 |
|------|------|
| `ErpPageHeader` | 椤甸潰鏍囬 + 璇存槑 + 鎿嶄綔鎸夐挳 |
| `ErpEmptyState` | 涓枃绌虹姸鎬?|
| `ErpErrorState` | 涓枃閿欒 + 閲嶈瘯 |
| `ErpLoadingSkeleton` | 鍔犺浇楠ㄦ灦灞?|
| `ErpStatusBadge` | 鐘舵€佹爣绛撅紙30+ 涓枃鏄犲皠锛?|
| `ErpRiskBadge` | 椋庨櫓鏍囩锛堜綆/涓?楂橈級 |
| `ErpMetricCard` | 鎸囨爣鍗＄墖 |
| `ErpFeatureUnderConstruction` | 鍔熻兘寤鸿涓崰浣?|

---

## 浜斻€佸墠绔〉闈紙53 涓寮忚矾鐢遍〉闈?+ 2 涓潪姝ｅ紡缁勪欢锛?
### 缁忚惀涓績
| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 1 | 缁忚惀椹鹃┒鑸?| `/` | 鉁?|
| 2 | 鏁版嵁浠〃鐩?| `/dashboard` | 鉁?|
| 3 | 浠婃棩寰呭姙 | `/today-actions` | 鉁?|
| 4 | 瀹℃壒涓績 | `/approvals` | 鉁?|

### 閿€鍞鐞?| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 5 | 璁㈠崟绠＄悊 | `/orders` | 鉁?|
| 6 | 閫€璐х鐞?| `/returns` | 鉁?|
| 7 | 閫€娆剧鐞?| `/refunds` | 鉁?|
| 8 | 涔板娑堟伅 | `/buyer-messages` | 鉁?|

### 骞垮憡绠＄悊
| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 9 | 骞垮憡鐩爣 | `/goals` | 鉁?|
| 10 | 鏂板缓鐩爣 | `/goals/new` | 鉁?|
| 11 | 鐩爣璇︽儏 | `/goals/:id` | 鉁?|
| 12 | 骞垮憡娲诲姩 | `/campaigns` | 鉁?|
| 13 | 鍏抽敭璇?| `/keywords` | 鉁?|
| 14 | 鍏抽敭璇嶆櫤鑳?| `/keyword-intelligence` | 鉁?|
| 15 | 鎼滅储璇?| `/search-terms` | 鉁?|
| 16 | AI 浼樺寲寤鸿 | `/recommendations` | 鉁?|

### 鍟嗗搧绠＄悊
| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 17 | 浜у搧鍒楄〃 | `/products` | 鉁?|
| 18 | Listing AI | `/products/:id/listing-ai` | 鉁?|
| 19 | 浜у搧涓婁紶 | `/product-upload` | 鉁?|

### 搴撳瓨涓庝粨搴?| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 20 | 搴撳瓨鍋ュ悍 | `/inventory-health` | 鉁?|
| 21 | 琛ヨ揣寤鸿 | `/replenishment` | 鉁?|
| 22 | 浠撳簱绠＄悊 | `/warehouses` | 鉁?|

### 閲囪喘涓庝緵搴旈摼
| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 23 | 閲囪喘璁㈠崟 | `/purchase-orders` | 鉁?|
| 24 | 渚涘簲鍟嗙鐞?| `/suppliers` | 鉁?|
| 25 | FBA 璐т欢 | `/fba-shipments` | 鉁?|

### 璐㈠姟绠＄悊
| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 26 | 鍒╂鼎鐪嬫澘 | `/profit-dashboard` | 鉁?|
| 27 | SKU 鍒╂鼎 | `/product-profit` | 鉁?|
| 28 | 缁撶畻绠＄悊 | `/settlements` | 鉁?|

### 瀹㈡湇涓庤瘎浠?| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 29 | 瀹㈡湇宸ュ崟 | `/customer-tickets` | 鉁?|
| 30 | Review 绠＄悊 | `/reviews` | 鉁?|
| 31 | Feedback 绠＄悊 | `/feedback` | 鉁?|

### 鑷姩鍖?| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 32 | 鍏ㄩ儴浠诲姟 | `/tasks` | 鉁?|
| 33 | 鑷姩鍖栬鍒?| `/automation-rules` | 鉁?|
| 34 | 椋炰功閰嶇疆 | `/integrations/feishu` | 鉁?|
| 35 | 瀹¤鍥炴粴 | `/audit-rollback` | 鉁?|

### 鏁版嵁涓績
| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 36 | CSV 瀵煎叆 | `/imports` | 鉁?|
| 37 | 鏁版嵁璐ㄩ噺 | `/data-quality` | 鉁?|
| 38 | API 杩炴帴 | `/api-connections` | 鉁?|
| 39 | 骞冲彴鍚屾 | `/platform-sync` | 鉁?|
| 40 | 鍚屾鏃ュ織 | `/sync-logs` | 鉁?|
| 41 | 鎶ヨ〃涓績 | `/reports` | 鉁?|

### 绯荤粺璁剧疆
| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 42 | 搴楅摵绠＄悊 | `/stores` | 鉁?|
| 43 | 鐢ㄦ埛绠＄悊 | `/users` | 鉁?|
| 44 | 瑙掕壊绠＄悊 | `/roles` | 鉁?|
| 45 | 閮ㄩ棬绠＄悊 | `/departments` | 鉁?|
| 46 | 鏉冮檺绠＄悊 | `/permissions` | 鉁?|
| 47 | 鏁版嵁鏉冮檺 | `/data-scopes` | 鉁?|
| 48 | 鐧诲綍鏃ュ織 | `/login-logs` | 鉁?|
| 49 | 涓汉涓績 | `/profile` | 鉁?|
| 50 | 淇敼瀵嗙爜 | `/change-password` | 鉁?|
| 51 | 绯荤粺璁剧疆 | `/settings` | 鉁?|

### 璁よ瘉涓庢潈闄?| # | 椤甸潰 | 璺敱 | 鐘舵€?|
|---|------|------|------|
| 52 | 鐧诲綍椤?| `/login` | 鉁?|
| 53 | 鏃犳潈闄愰〉 | `/403` | 鉁?|

### 闈炴寮忛〉闈紙涓嶈鍏ユ寮忛〉闈㈡暟閲忥級
| 椤甸潰 | 璇存槑 | 鐘舵€?|
|------|------|------|
| ComingSoonPage.tsx | 宸ュ叿缁勪欢锛屾湭琚矾鐢变娇鐢?| 鈿狅笍 淇濈暀 |
| BillingPage.tsx | 宸蹭粠璺敱鍜屽鑸Щ闄?| 鈿狅笍 褰掓。 |

---

## 鍏€佹暟鎹簱 Schema锛堝崟涓€ schema.sql锛?
鏁版嵁搴撲负 **MySQL 8.0**锛屾槸椤圭洰鍞竴鏉冨▉鏁版嵁婧愶紙Source of Truth锛夈€?*鏁版嵁搴撲笌搴旂敤瑙ｈ€?*锛氬叏閮ㄥ缓琛ㄤ笌绉嶅瓙鏁版嵁闆嗕腑鍦ㄥ崟涓€鏂囦欢 `backend-java/db/schema.sql`锛岀敱杩愮淮鎵嬪姩瀵煎叆锛涘簲鐢ㄥ惎鍔?*涓嶅仛杩佺Щ銆佷笉鑷姩寤鸿〃**锛坄flyway.enabled=false`銆乣ddl-auto=none`锛夈€傚悗缁?schema 鍙樻洿涔熷彧鍦ㄨ鏂囦欢鍐呬慨鏀广€?
- 鍏ㄩ噺 DDL锛?00+ 琛級+ 鍏ㄩ噺绉嶅瓙鏁版嵁锛圖ML锛屽箓绛?`INSERT IGNORE`锛屽惈 20 涓唴閮ㄨ处鍙凤級锛屽潎鍦?`schema.sql` 鍐呫€?- 鏂囦欢骞傜瓑锛坄CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`锛夛紝鍙噸澶嶅鍏ヤ互琛ラ綈缂哄け鐨勮〃/绉嶅瓙锛涗絾**宸插瓨鍦ㄧ殑琛ㄦ柊澧炲垪闇€鍗曠嫭鎵ц `ALTER`**銆?
浠ヤ笅涓?schema 瑕嗙洊鐨勮〃鍒嗙粍姒傝锛堟寜妯″潡褰掔被锛夈€?
### 琛ㄥ垎缁勶紙schema.sql锛?00+ 琛級
- 缁勭粐/鐢ㄦ埛/瑙掕壊/鏉冮檺/閮ㄩ棬/瀹℃壒/瀹¤
- 绔欑偣/搴楅摵/浜у搧/鎴愭湰/鍥剧墖
- 浠诲姟/鎶ュ憡/API杩炴帴
- 鐩爣/Campaign/鍏抽敭璇?鎼滅储璇?鎺ㄨ崘
- 鍏抽敭璇嶆礊瀵?瑕嗙洊/N-Gram/Listing/绔炲搧
- 涓婁紶/瀵煎叆/鍘熷鎶ヨ〃/鏁版嵁璐ㄩ噺
- 鍒╂鼎/鎴愭湰/搴撳瓨/琛ヨ揣
- 瀹℃壒/椋炰功/鑷姩鍖?瀹¤/鍥炴粴
- 璁㈠崟/閫€璐?閫€娆?缁撶畻/涔板娑堟伅
- 渚涘簲鍟?閲囪喘/浠撳簱/鍏ュ簱/鍑哄簱/璋冩嫧/鐩樼偣/璐ㄦ
- FBA璐т欢/澶寸▼鐗╂祦/鐗╂祦璐圭敤/鐜伴噾娴?搴旀敹/搴斾粯
- 瀹㈡湇宸ュ崟/Review/Feedback/Listing鐩戞帶/BuyBox/璺熷崠/璋冧环/淇冮攢/鍙樹綋
- 骞冲彴杩炴帴/API浠ょ墝/鍚屾浠诲姟/鍚屾鏃ュ織/瀹炰綋鏄犲皠
- AI 鎺ュ彛閰嶇疆 / Insight Agent 鎸佷箙鍖?/ 鏁版嵁婧愭縺娲?/ 椋炰功闆嗘垚锛堝惈 webhook 瀛楁锛?
### 绉嶅瓙鏁版嵁
- 鍐呴儴鐢ㄦ埛/瑙掕壊/鏉冮檺锛堝惈 20 涓唴閮ㄨ处鍙凤級銆佺珯鐐?搴楅摵/浜у搧銆佷换鍔?鎶ュ憡/瀹¤銆佸箍鍛?鍏抽敭璇?Listing銆佷笂浼?瀵煎叆銆佸埄娑?搴撳瓨銆佸鎵?椋炰功/鑷姩鍖栥€佽鍗?缁撶畻/閲囪喘/浠撳簱/鐗╂祦/璐㈠姟/瀹㈡湇/Review/骞冲彴杩炴帴銆?
> 鍘嗗彶涓婂垎鏁ｇ殑 13 浠?DDL銆?4 浠?DML銆乂1鈥揤13 杩佺Щ涓?15 浠?report 鏂囦欢锛屽凡鏁村悎杩涘崟涓€ `backend-java/db/schema.sql`锛屼綔涓哄敮涓€鏉冨▉ SQL 婧愩€?
---

## 涓冦€佹湰鍦板惎鍔ㄥ懡浠?
```bash
# 鍓嶇锛堝紑鍙戠鍙?5173锛?api 浠ｇ悊鍒板悗绔級
cd frontend && pnpm install && pnpm dev

# Java 鍚庣锛堢鍙?8090锛?cd backend-java && ./mvnw spring-boot:run
```

鏁版嵁搴撻渶鍏堟墜鍔ㄥ鍏ヤ竴娆?鍒涘缓绌虹殑 MySQL `adpilot` 搴撳悗鎵ц `mysql -u <user> -p adpilot < backend-java/db/schema.sql`锛堣瑙佲€滃崄浜屻€侀儴缃叉寚鍗椻€濓級銆傚簲鐢ㄥ惎鍔ㄤ笉浼氳嚜鍔ㄥ缓琛ㄣ€?
---

## 鍏€佸紑鍙戣矾绾垮浘

### 鉁?Phase A锛欽ava 鍚庣鏍稿績閾捐矾锛堝凡瀹屾垚锛?### 鉁?Phase B锛歋QL 鏂囦欢浣撶郴钀藉湴锛堝凡瀹屾垚锛?### 鉁?Phase C锛欰dvertising + Keyword + Listing + Upload锛堝凡瀹屾垚锛?### 鉁?Phase D锛欳SV Import + Data Quality锛堝凡瀹屾垚锛?### 鉁?Phase E锛歅rofit + Inventory + Replenishment锛堝凡瀹屾垚锛?### 鉁?Phase F锛氶涔﹀鎵?+ Automation + AI Audit + Rollback锛堝凡瀹屾垚锛?### 鉁?Phase G锛氶鏄熷紡 ERP 妯″潡 SQL + Java 瀹炵幇锛堝凡瀹屾垚锛?### 鉁?Phase H锛氬钩鍙?API 杩炴帴妗嗘灦 SQL + Java 瀹炵幇锛堝凡瀹屾垚锛?### 鉁?涓枃鍖?+ ERP UI 缁勪欢 + 鍓嶇绋冲畾鎬э紙宸插畬鎴愶級
### 鉁?V1.3锛氳处鍙锋潈闄愮郴缁燂紙宸插畬鎴愶級

---

## 涔濄€乂1.3 璐﹀彿鏉冮檺绯荤粺

### 鐧诲綍涓庤璇?
| 鍔熻兘 | 璇存槑 | 鐘舵€?|
|------|------|------|
| 鐧诲綍椤甸潰 | `/login` - 浼佷笟鍐呴儴 ERP 椋庢牸 | 鉁?|
| 鐧诲綍鎺ュ彛 | `POST /api/auth/login` - JWT 璁よ瘉 | 鉁?|
| 鐧诲嚭鎺ュ彛 | `POST /api/auth/logout` | 鉁?|
| 褰撳墠鐢ㄦ埛 | `GET /api/auth/me` | 鉁?|
| 淇敼瀵嗙爜 | `POST /api/auth/change-password` | 鉁?|
| 璺敱瀹堝崼 | `AuthGuard` - 鏈櫥褰曡烦杞?/login | 鉁?|
| 鏉冮檺瀹堝崼 | `PermissionGuard` - 鎸夐挳绾ф潈闄?| 鉁?|
| 403 椤甸潰 | 鏃犳潈闄愯闂彁绀?| 鉁?|

### 鏉冮檺浣撶郴

| 缁勪欢 | 璇存槑 | 鏂囦欢 |
|------|------|------|
| `auth.ts` | Token 绠＄悊銆佺敤鎴风姸鎬併€乤uthFetch | `lib/auth.ts` |
| `permission.ts` | 鏉冮檺妫€鏌ュ嚱鏁?| `lib/permission.ts` |
| `AuthGuard.tsx` | 璺敱绾ф潈闄愬畧鍗?| `components/AuthGuard.tsx` |
| `PermissionGuard.tsx` | 鎸夐挳绾ф潈闄愬畧鍗?| `components/PermissionGuard.tsx` |

### 20 涓唴閮ㄨ处鍙?
| 閭 | 濮撳悕 | 瑙掕壊 | 閮ㄩ棬 |
|------|------|------|------|
| admin@adpilot.local | 绯荤粺绠＄悊鍛?| 瓒呯骇绠＄悊鍛?| 鎶€鏈儴 |
| boss@adpilot.local | 寮犳€?| 绠＄悊灞?| 鎬荤粡鐞嗗姙 |
| ops.manager@adpilot.local | 鏉庢槑 | 杩愯惀涓荤 | 杩愯惀閮?|
| ops01@adpilot.local | 鐜嬭姵 | 杩愯惀涓撳憳 | 杩愯惀閮?|
| ops02@adpilot.local | 鍒樻磱 | 杩愯惀涓撳憳 | 杩愯惀閮?|
| ads01@adpilot.local | 闄堢 | 骞垮憡鎶曟墜 | 骞垮憡閮?|
| ads02@adpilot.local | 璧靛┓ | 骞垮憡鎶曟墜 | 骞垮憡閮?|
| finance01@adpilot.local | 閽变細璁?| 璐㈠姟 | 璐㈠姟閮?|
| finance02@adpilot.local | 瀛欏嚭绾?| 璐㈠姟 | 璐㈠姟閮?|
| logistics01@adpilot.local | 鍛ㄧ墿娴?| 鐗╂祦 | 鐗╂祦閮?|
| logistics02@adpilot.local | 鍚磋繍杈?| 鐗╂祦 | 鐗╂祦閮?|
| warehouse01@adpilot.local | 閮戜粨绠?| 浠撳簱 | 浠撳簱閮?|
| warehouse02@adpilot.local | 鍐簱绠?| 浠撳簱 | 浠撳簱閮?|
| procurement01@adpilot.local | 闊╅噰璐?| 閲囪喘 | 閲囪喘閮?|
| procurement02@adpilot.local | 鏉ㄩ噰涔?| 閲囪喘 | 閲囪喘閮?|
| cs01@adpilot.local | 鏈卞鏈?| 瀹㈡湇 | 瀹㈡湇閮?|
| cs02@adpilot.local | 绉︽湇鍔?| 瀹㈡湇 | 瀹㈡湇閮?|
| product01@adpilot.local | 灏や骇鍝?| 浜у搧缁忕悊 | 浜у搧閮?|
| designer01@adpilot.local | 璁歌璁?| 璁捐 | 璁捐閮?|
| viewer01@adpilot.local | 浣曡瀵?| 鍙瑙傚療鍛?| 鎬荤粡鐞嗗姙 |

榛樿瀵嗙爜锛歚Adpilot@123456`锛堜粎寮€鍙戠幆澧冿紝涓婄嚎鍓嶅繀椤讳慨鏀癸級

### 瑙掕壊鏉冮檺鐭╅樀

| 瑙掕壊 | 缁忚惀涓績 | 閿€鍞鐞?| 骞垮憡绠＄悊 | 鍟嗗搧绠＄悊 | 搴撳瓨浠撳簱 | 閲囪喘渚涘簲閾?| 璐㈠姟绠＄悊 | 瀹㈡湇璇勪环 | 鑷姩鍖?| 鏁版嵁涓績 | 绯荤粺璁剧疆 |
|------|---------|---------|---------|---------|---------|-----------|---------|---------|--------|---------|---------|
| 瓒呯骇绠＄悊鍛?| 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 |
| 绠＄悊灞?| 鏌ョ湅/瀹℃壒 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 |
| 杩愯惀涓荤 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 鍏ㄩ儴 | 閮ㄥ垎 | 閮ㄥ垎 | 鏌ョ湅 | 閮ㄥ垎 | 閮ㄥ垎 | 鍏ㄩ儴 | 鏌ョ湅 |
| 杩愯惀涓撳憳 | 鏌ョ湅 | 閮ㄥ垎 | 閮ㄥ垎 | 鍏ㄩ儴 | 閮ㄥ垎 | 閮ㄥ垎 | - | 閮ㄥ垎 | - | 閮ㄥ垎 | - |
| 骞垮憡鎶曟墜 | - | - | 鍏ㄩ儴 | - | - | - | - | - | - | 閮ㄥ垎 | - |
| 璐㈠姟 | - | - | - | - | - | - | 鍏ㄩ儴 | - | - | 閮ㄥ垎 | - |
| 鐗╂祦 | - | - | - | - | 閮ㄥ垎 | - | 閮ㄥ垎 | - | - | - | - |
| 浠撳簱 | - | - | - | - | 鍏ㄩ儴 | - | - | - | - | - | - |
| 閲囪喘 | - | - | - | - | 閮ㄥ垎 | 鍏ㄩ儴 | - | - | - | - | - |
| 瀹㈡湇 | - | 閮ㄥ垎 | - | - | - | - | - | 鍏ㄩ儴 | - | - | - |
| 浜у搧缁忕悊 | - | - | - | 鍏ㄩ儴 | - | - | - | 閮ㄥ垎 | - | 閮ㄥ垎 | - |
| 璁捐 | - | - | - | 鏌ョ湅 | - | - | - | 鏌ョ湅 | - | - | - |
| 鍙瑙傚療鍛?| 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | 鏌ョ湅 | - |

### 鏉冮檺鏍煎紡

鏉冮檺缂栫爜鏍煎紡锛歚module:action`

绀轰緥锛?- `dashboard:view` 鈥?鏌ョ湅浠〃鐩?- `product:create` 鈥?鍒涘缓浜у搧
- `advertising:manage` 鈥?绠＄悊骞垮憡
- `finance:approve` 鈥?瀹℃壒璐㈠姟鎿嶄綔
- `user:manage` 鈥?绠＄悊鐢ㄦ埛

### 鏁版嵁鏉冮檺

| 鑼冨洿绫诲瀷 | 璇存槑 | 閫傜敤瑙掕壊 |
|---------|------|---------|
| 鍏ㄥ叕鍙?| 鍙煡鐪嬫墍鏈夋暟鎹?| 瓒呯骇绠＄悊鍛樸€佺鐞嗗眰 |
| 鎸囧畾閮ㄩ棬 | 鍙煡鐪嬫湰閮ㄩ棬鏁版嵁 | 閮ㄩ棬涓荤 |
| 鎸囧畾搴楅摵 | 鍙煡鐪嬫寚瀹氬簵閾烘暟鎹?| 杩愯惀銆佸箍鍛?|
| 鎸囧畾绔欑偣 | 鍙煡鐪嬫寚瀹氱珯鐐规暟鎹?| 杩愯惀 |
| 鎸囧畾绫荤洰 | 鍙煡鐪嬫寚瀹氫骇鍝佺被鐩?| 浜у搧缁忕悊 |
| 浠呰嚜宸?| 鍙兘鏌ョ湅鑷繁璐熻矗鐨勬暟鎹?| 杩愯惀涓撳憳銆佸鏈?|
| 鍙 | 鍙兘鏌ョ湅锛屼笉鑳芥搷浣?| 鍙瑙傚療鍛樸€佽璁?|

### 瀹℃壒鏉冮檺

| 椋庨櫓绛夌骇 | 鍙鎵硅鑹?|
|---------|-----------|
| 浣庨闄?| 杩愯惀涓荤銆佺鐞嗗眰銆佽秴绾х鐞嗗憳 |
| 涓闄?| 绠＄悊灞傘€佽秴绾х鐞嗗憳 |
| 楂橀闄?| 浠呯鐞嗗眰/瓒呯骇绠＄悊鍛?|
| 涓ラ噸椋庨櫓 | 浠呰秴绾х鐞嗗憳锛堥鐣欏弻浜哄鎵癸級 |

---

## 鍗併€佷骇鍝佽璁★紙Product Spec 鎽樿锛?
**浜у搧瀹氫綅**锛氫互"鐩爣椹卞姩骞垮憡浼樺寲"锛圙oal-Based Ads Optimization锛変负鏍稿績 鈥斺€?鐢ㄦ埛杈撳叆涓氬姟鐩爣锛堝鎻愬崌閿€閲忋€佹帶鍒?ACoS銆佸搧鐗岄槻瀹堛€佺被鐩墿寮犮€佺珵鍝佹埅娴併€佹竻搴撳瓨銆佹柊鍝佸喎鍚姩銆佸叧閿瘝鎺掑悕鎻愬崌锛夛紝绯荤粺鑷姩鐢熸垚骞垮憡缁撴瀯銆侀绠楀垎閰嶃€佸叧閿瘝涓庣珵鍝佺瓥鐣ャ€佸嚭浠蜂笌鍚﹁瘝寤鸿锛屼互鍙婂箍鍛婂鐩樻姤鍛娿€?
**鍝佺墝鍙ｅ彿**锛歋et your goal. We optimize the rest.锛堣瀹氱洰鏍囷紝鎴戜滑浼樺寲涓€鍒囥€傦級

**鏍稿績宸紓鍖?*锛氫互鐩爣涓哄厛鐨?Campaign 鑷姩鍖栵紝閰嶅悎鍙В閲婄殑 AI 浼樺寲寤鸿銆?
### 鐩爣绫诲瀷锛? 绉嶏級
鍐峰惎鍔紙launch锛? 鍒╂鼎锛坧rofit锛? 澧為暱锛坓rowth锛? 鍝佺墝闃插畧锛坆rand_defense锛? 绔炲搧鎴祦锛坈ompetitor锛? 绫荤洰鎷撳睍锛坈ategory锛? 娓呭簱瀛橈紙clearance锛? 鎺掑悕鎻愬崌锛坮ank_boost锛夈€?
### 鏍稿績涓氬姟娴佺▼
杈撳叆鐩爣 鈫?閫夌洰鏍囩被鍨?鈫?閫変骇鍝?SKU/ASIN 鈫?閰嶇疆鐩爣鍙傛暟锛圓CoS銆侀绠椼€丆PC銆佸嚭浠疯寖鍥达級鈫?杈撳叆鍏抽敭璇嶄笌绔炲搧淇℃伅 鈫?璁剧疆鑷姩鍖栧紑鍏筹紙鑷姩鍚﹁瘝/璋冧环/鎵╄瘝锛夆啋 绯荤粺鐢熸垚 Campaign 缁撴瀯 鈫?瀛︿範鏈燂紙3 澶╋級鈫?鎸佺画鐩戞帶 鈫?鐢熸垚 AI 浼樺寲寤鸿 鈫?鐢ㄦ埛瀹℃壒/鑷姩鎵ц 鈫?鎸佺画浼樺寲寰幆銆?
### 骞垮憡浼樺寲瑙勫垯鎽樿

**鍏抽敭璇嶉噰闆嗭紙Harvesting锛?*
| 鏉′欢 | 鍔ㄤ綔 |
|------|------|
| 浜х敓璁㈠崟 涓?ACoS < 鐩爣 ACoS | 鍔犲叆绮惧噯鍖归厤锛圗xact锛?|
| 鐐瑰嚮閲忛珮 浣?0 璁㈠崟 | 鍔犲叆鍚﹁瘝鍊欓€?|
| CTR 楂?浣?CVR 浣?| 闄嶄綆鍑轰环 |
| CVR 楂?浣?鏇濆厜浣?| 鎻愰珮鍑轰环 / 寮€鍚?Keyword Boost |
| 绔炲搧璇?/ 绫荤洰璇?| 褰掑叆绔炲搧 / 绫荤洰 Campaign |

**鍑轰环浼樺寲锛圔id锛?*锛氱洰鏍?CPC = 鐩爣 ACoS 脳 杞寲鐜?脳 瀹㈠崟浠枫€傚畨鍏ㄦ満鍒讹細鍗曟璋冩暣骞呭害 鈮?20%锛屽嚭浠蜂笉浣庝簬 `min_bid`銆佷笉楂樹簬 `max_bid`锛屽涔犳湡锛堝墠 3 澶╋級涓嶅仛婵€杩涜皟鏁淬€?
**棰勭畻浼樺寲锛圔udget锛?*锛欰CoS 楂?ROAS 浣庡垯鍑忛绠楋紱ACoS 浣?ROAS 楂樹笖搴撳瓨鍏呰冻鍒欏棰勭畻锛涜妭鏃ユ椿鍔ㄥ簲鐢ㄩ绠楀€嶇巼锛涜秴杩囪处鎴蜂笂闄愬垯闃绘澧炲姞銆?
### AI 鎺ㄨ崘鍙В閲婃€у師鍒?- 涓嶅彧缁欑粨璁猴紝蹇呴』缁欏師鍥狅紙瀹屾暣鐨?涓轰粈涔?锛?- 涓嶅彧缁欐柟鍚戯紝蹇呴』缁欐暟鎹紙褰撳墠鎸囨爣銆佺洰鏍囨寚鏍囥€佸巻鍙茶秼鍔匡級
- 涓嶅彧缁欏缓璁紝蹇呴』缁欓鏈燂紙棰勮鑺傜渷/鎻愬崌锛?- 涓嶅彧缁欐搷浣滐紝蹇呴』缁欓闄╋紙浣?涓?楂?椋庨櫓绛夌骇锛?
鎺ㄨ崘绫诲瀷鍏?15 绉嶏細澧炲姞/鍑忓皯棰勭畻銆佹彁楂?闄嶄綆鍑轰环銆佹坊鍔犲叧閿瘝/鍚﹁瘝/绔炲搧 ASIN銆佹殏鍋滄姇鏀俱€佸叧閿瘝鍔犻€熴€佺Щ鍏ョ簿鍑嗗尮閰嶃€佹媶鍒?Campaign銆佸簱瀛橀璀︺€丄CoS 瓒呮爣棰勮銆佹洕鍏変笉瓒抽璀︺€佹帓鍚嶆満浼氥€?
---

## 鍗佷竴銆侀泦鎴愪笌杩炴帴鏂瑰紡

### 鑳藉姏姒傝

AdPilot AI 鍦ㄤ竴涓悗鍙伴噷鐢?AI 鐩戞帶骞剁鐞嗗涓簵閾虹殑骞垮憡涓庣粡钀ユ暟鎹€傛牳蹇冭兘鍔涳細

- **澶氬簵閾恒€佸娓犻亾闅旂**锛氬簵閾烘寜骞冲彴鏃忥紙`amazon` / `independent_site` / `tiktok` / `logistics` / `finance`锛夊綊灞烇紝杩炴帴銆佽鍙栥€佸垏鎹㈠潎鎸夊钩鍙版棌闅旂锛涢殧绂荤敱鍚庣 `PlatformAccessAspect` + `DataScopeService` 寮哄埗锛屽墠绔殣钘忔帶浠朵粎涓轰綋楠屼紭鍖栵紝涓嶄綔涓哄畨鍏ㄦ墜娈点€?- **鍗曚骇鍝?AI 骞垮憡鍒涘缓**锛氬湪鏌愪釜浜у搧涓婄洿鎺ュ脊绐椼€佸～鍑犱釜鑳界湅鎳傜殑鍙傛暟锛堥绠椼€丄I 浜烘牸銆佹墭绠″紑鍏炽€佸畨鍏ㄨ竟鐣屻€佹墽琛屾ā寮忥級灏辫兘鍒涘缓璇ヤ骇鍝佺殑鍏抽敭璇嶅箍鍛婏紝鏃犻渶鐞嗚В搴曞眰骞垮憡缁撴瀯銆?- **浜氶┈閫婂箍鍛婃暟鎹彲瑙佹€?*锛氫互浜у搧涓鸿瑙掕仛鍚堝叾鍏宠仈娲诲姩涓庤〃鐜版暟鎹紙鑺辫垂/鐐瑰嚮/璁㈠崟/閿€鍞/ACoS锛夛紝鏍囨槑鏁版嵁涓哄垵姝ワ紙`preliminary`锛夋垨鏈€缁堬紙`finalized`锛夛紝骞惰娓?T+1 涓庡綊鍥犲欢杩熴€?- **鐙珛绔欑粡钀ュ洖鍐?*锛氬湪鏈?Shopify/WooCommerce 杩炴帴鏃跺洖鍐欏簱瀛樹笌鍙戣揣锛堝綋鍓嶈兘鍔涜竟鐣岃鈥滃綋鍓嶉檺鍒垛€濓級銆?- **鎸夎处鍙枫€佹寜搴楅摵鐙珛鐨勯涔﹂€氱煡**锛氭瘡涓处鍙风粦瀹氳嚜宸辩殑椋炰功銆佹瘡涓簵閾虹殑閫氱煡鍙繘瀹冭嚜宸辩粦瀹氱殑浼氳瘽銆?
### 椤跺眰瀵艰埅鍧楋紙Nav_Block锛夋€昏

瀵艰埅鐢?`frontend/src/app/lib/navConfig.ts` 閰嶇疆涓轰簲涓《灞傚潡锛屾瘡鍧楀搴斾竴涓钩鍙版棌锛圥latform_Family锛夛紝璐﹀彿鑳借繘鍏ュ摢浜涘潡鐢卞叾 `Platform_Access` 鍐冲畾锛堟棤鏉冮檺鐨勫潡瀹屽叏涓嶆覆鏌擄紝瓒婃潈璇锋眰鍚庣涓€寰?403锛夛細

| Nav_Block | 骞冲彴鏃?`key` | 鐢ㄩ€?| 鍙鏉′欢锛圥latform_Access锛?|
|-----------|-------------|------|------------------------------|
| 浜氶┈閫?| `amazon` | 浜氶┈閫婂簵閾虹殑骞垮憡鐩爣/娲诲姩/鍏抽敭璇?鎼滅储璇嶃€丄I 浼樺寲寤鸿銆佹姤琛ㄥ悓姝ヤ笌鍗曚骇鍝佸箍鍛婂垱寤?| 鍚?`amazon` |
| 鐙珛绔?| `independent_site` | WordPress/WooCommerce 涓?Shopify 搴楅摵鐨?Google 骞垮憡锛堟寜鏉冮檺鏄剧ず锛夈€佸簱瀛?鍙戣揣鍥炲啓 | 鍚?`independent_site` |
| 鐗╂祦 | `logistics` | 澶寸▼鐗╂祦銆丗BA 璐т欢銆佺墿娴佽垂鐢ㄧ瓑鐗╂祦瑙嗗浘 | 鍚?`logistics` |
| 璐㈠姟缁熻 | `finance` | 鍒╂鼎鐪嬫澘銆佺粨绠椼€佺幇閲戞祦/搴旀敹/搴斾粯绛夎储鍔＄粺璁?| 鍚?`finance` |
| TikTok | `tiktok` | TikTok 搴楅摵杩炴帴涓庤鍗?鍟嗗搧鏁版嵁鍚屾锛堢嫭绔嬫垚鍧楋紝宸蹭粠鐙珛绔欑Щ鍑猴級 | 鍚?`tiktok` |

> Google 骞垮憡鐩稿叧瀵艰埅椤瑰綊灞炲湪鈥滅嫭绔嬬珯鈥濆潡涓嬶紝浠呭綋璐﹀彿 `Platform_Access` 鍚?`independent_site` 涓旀寔鏈?`advertising:view` 鏃舵樉绀猴紱鏉冮檺鍙樺寲鍦ㄤ笅涓€娆℃媺鍙?`GET /api/auth/me` 鍚庡嵆鏃剁敓鏁堬紝鏃犻渶閲嶆柊鐧诲綍銆?
### 鍚勫钩鍙版棌鐨勮繛鎺ユ柟寮?
- **浜氶┈閫婏紙`amazon`锛?*锛氳涓嬫枃鈥滀簹椹€婂箍鍛婃巿鏉冿紙Amazon Ads OAuth锛夆€濄€傚湪鈥淎PI 杩炴帴鈥濋〉璧般€岃繛鎺ヤ簹椹€婂簵閾恒€嶅悜瀵硷紙鍖哄煙 鈫?LWA 鎺堟潈 鈫?閫夊箍鍛?Profile 缁戝畾搴楅摵锛夈€?- **鐙珛绔欙紙`independent_site`锛?*锛氬湪鐙珛绔欏潡鍐呰繛鎺?Shopify 鎴?WooCommerce 搴楅摵锛堣繛鎺ュ叆鍙ｇ殑骞冲彴鑼冨洿闄愬畾涓?`independent_site` 鏃忥紝鍙垱寤?`shopify`/`woocommerce` 杩炴帴锛夈€?- **TikTok锛坄tiktok`锛?*锛氬湪 TikTok 鍧楀唴杩炴帴锛堣繛鎺ュ叆鍙?`to: '/data-sync?platform=tiktok'`锛屽钩鍙拌寖鍥撮檺瀹氫负 `tiktok` 鏃忥紝鍙垱寤?`tiktok_shop` 绛?TikTok 鏃忚繛鎺ワ級锛涗笉鍐嶄粠鐙珛绔欏潡杩炴帴 TikTok銆?- **鍚屾棌绾︽潫**锛氭瘡涓潡鍐呭彧鑳藉垱寤轰笌璇ュ潡骞冲彴鏃忎竴鑷寸殑杩炴帴锛堜簹椹€婅繛浜氶┈閫娿€佺嫭绔嬬珯杩炵嫭绔嬬珯銆乀ikTok 杩?TikTok锛夛紱璺ㄦ棌鎿嶄綔鍚庣浠?403 鎷掔粷銆?
### 鍗曚骇鍝?AI 骞垮憡鍒涘缓锛圥roductAdModal 鈫?`POST /api/product-ads/campaign`锛?
浠庘€滃晢鍝佺鐞?浜у搧璇︽儏鈥濆叆鍙ｅ鏌愪釜浜у搧鐐光€滃垱寤哄箍鍛娾€濓紝鎵撳紑 `ProductAdModal` 寮圭獥锛?
1. **鍏ュ彛鍙鏉′欢**锛氱櫥褰曡处鍙风殑 `Platform_Access` 鍚浜у搧鎵€灞炲簵閾虹殑骞冲彴鏃忥紝涓旀寔鏈?`advertising:view` 涓?`advertising:manage`锛堝箍鍛婂垱寤烘潈闄愶級銆?2. **閲囬泦鍙傛暟**锛氶绠楅噾棰濄€侀绠楃被鍨嬶紙`budget_type`锛夈€丄I 浜烘牸锛堜繚瀹?鍧囪　/婵€杩涳級銆佹槸鍚︽墭绠★紙`hosting_enabled`锛夈€佸畨鍏ㄨ竟鐣岋紙鐩爣 ACoS銆佸嚭浠蜂笂涓嬮檺銆侀绠椾笂涓嬮檺锛夈€佹墽琛屾ā寮忥紙Execution_Mode锛屽彲涓嶉€夛級銆?3. **鎵ц妯″紡榛樿**锛氭湭鏄惧紡閫夋嫨鏃舵寜 `observe_only`锛堜粎瑙傚療锛夊鐞嗐€傚彲閫夊€硷細`observe_only` / `recommend_only` / `approval_required` / `auto_execute`銆?4. **鏍￠獙**锛氶绠椼€佺洰鏍?ACoS銆佸嚭浠?棰勭畻涓婁笅闄愰』涓烘鏁帮紝涓斾笂闄愪笉灏忎簬涓嬮檺锛涗笉婊¤冻鏃跺悗绔繑鍥炴寚鏄庡瓧娈电殑 400 鏍￠獙閿欒锛屼笖涓嶅垱寤轰换浣曟椿鍔ㄣ€佸叧鑱旀垨閰嶇疆銆?5. **鎻愪氦鍚庣**锛氬脊绐楄皟鐢?`POST /api/product-ads/campaign`锛屽悗绔湪鍗曚釜浜嬪姟鍐咃細缁?`CampaignBuilderService` 寤哄叧閿瘝骞垮憡娲诲姩 鈫?閫氳繃 `campaign_product_link` 灏嗘椿鍔ㄥ叧鑱斿埌璇ヤ骇鍝侊紙ASIN锛夆啋锛堣嫢寮€鍚墭绠★級钀?`HostingConfig`锛堝惈鎵ц妯″紡锛変笌 `SafetyBoundary` 鈫?缁?Operation-Outbox 钀?Operation+Outbox 寮傛鍥炲啓銆備换涓€姝ュけ璐ユ暣浣撳洖婊氥€?6. **寮傛鍥炲啓**锛氬浜氶┈閫婂箍鍛?API 鐨勫啓鍏ョ粷涓嶅湪璇锋眰绾跨▼鍐呭悓姝ヨ皟鐢紝鑰屾槸鐢?`OutboxWorker` 寮傛鎻愪氦銆?7. **杩斿洖**锛氭柊寤烘椿鍔ㄦ爣璇嗐€佽鍏宠仈鐨勪骇鍝佹爣璇嗕笌璇ユ椿鍔ㄥ綋鍓嶇殑鎵ц妯″紡銆?
> 鍗曚骇鍝佸叧閿瘝骞垮憡鍒涘缓鐩墠闄愬畾浜氶┈閫婂钩鍙版棌锛堢鐐瑰甫 `@RequirePlatform(PlatformFamily.AMAZON)`锛夈€?
### 椋炰功鏈哄櫒浜猴紙鍗曞悜閫氱煡锛?- 浠呬娇鐢?*鑷畾涔夋満鍣ㄤ汉 Webhook**锛氬湪椋炰功缇?鈫?缇ゆ満鍣ㄤ汉 鈫?娣诲姞銆岃嚜瀹氫箟鏈哄櫒浜恒€嶁啋 澶嶅埗 Webhook 鍦板潃锛堝彲閫夌鍚嶅瘑閽ワ級鈫?鍦ㄣ€岄涔︽満鍣ㄤ汉銆嶉〉绮樿创杩炴帴 鈫?鍙戦€佹祴璇曘€?- 鐢ㄩ€旓細AI 骞垮憡璇婃柇銆佸紓甯告彁閱掋€佹棩鎶?鍛ㄦ姤鎺ㄩ€併€傛棤闇€鍒涘缓搴旂敤銆佹棤闇€缁戝畾缇よ亰銆?- **鎸夎处鍙枫€佹寜搴楅摵鐙珛缁戝畾**锛氭瘡涓处鍙风敤鑷繁鐨勯涔﹀嚟鎹负鎸囧畾搴楅摵缁戝畾锛堟寜 `storeId` + 璐﹀彿 `owner_account_id` + `connectionType` 淇濆瓨鍒?`feishu_integrations`锛岄渶 `feishu:manage` 鏉冮檺锛夈€傛煇搴楅摵鐨勯€氱煡鍙細鐢ㄢ€滆搴楅摵瀵瑰簲璐﹀彿鎵€缁戝畾鐨勯泦鎴愨€濆彂閫佸埌鍏剁粦瀹氫細璇濓紝**缁濅笉璺ㄨ处鍙峰鐢ㄥ嚟鎹€佺粷涓嶄负鏈粦瀹氱殑搴楅摵鍙戦€?*銆?- **鏃犵粦瀹氫紭闆呰烦杩?*锛氳嫢鏌愬簵閾烘病鏈夋湁鏁堢殑椋炰功缁戝畾锛岀郴缁熻烦杩囪搴楅摵鐨勫彂閫佸苟璁板綍鍙璺宠繃鍘熷洜锛屼笉浼氭姤閿欎腑鏂叾浠栧簵閾虹殑閫氱煡锛屼篃涓嶄細鍙戝埌閿欒鐨勪細璇濄€?- **娴嬭瘯杩炴帴**锛氶厤缃椂鐐光€滄祴璇曡繛鎺モ€濈粡 `POST /api/integrations/feishu/{id}/test-message` 鐢ㄨ缁戝畾鍑嵁楠岃瘉杩為€氭€с€傞潪瓒呯骇绠＄悊鍛樺彧鑳芥煡鐪?绠＄悊鍏?`Store_Group_Scope` 鍐呭簵閾虹殑椋炰功闆嗘垚銆?- 楂樼骇鑳藉姏锛堜簨浠惰闃呰嚜鍔ㄧ粦缇ゃ€佷氦浜掑崱鐗囩‘璁ゅ洖璋冿級鍦ㄥ悗绔?API 灞備繚鐣欙紝鍥炶皟鍦板潃锛?  - 浜嬩欢璁㈤槄锛歚POST https://api.your-domain.example/api/integrations/feishu/event`
  - 鍗＄墖鍥炶皟锛歚POST https://api.your-domain.example/api/integrations/feishu/card-callback`
  - 鍗＄墖"鎵瑰噯/椹冲洖"浼氬洖鍐欏叧鑱旂殑瀹℃壒璇锋眰锛堟搷浣滀汉缁?`feishu_user_bindings` 鏄犲皠锛夈€?
### 浜氶┈閫婂箍鍛婃巿鏉冿紙Amazon Ads OAuth锛?- 銆孉PI 杩炴帴銆嶉〉鐨勩€岃繛鎺ヤ簹椹€婂簵閾恒€嶅悜瀵硷細鍖哄煙(NA/EU/FE) 鈫?LWA 鎺堟潈 鈫?閫夊箍鍛?Profile 缁戝畾搴楅摵銆?- 鍥炶皟鍦板潃锛堥渶鐧昏鍒?LWA 搴旂敤 Allowed Return URL锛夛細`https://your-domain.example/api-connections`
- 鍑瘉鐜鍙橀噺锛歚ADPILOT_AMAZON_ADS_CLIENT_ID/SECRET/REDIRECT_URI`锛坰cope `advertising::campaign_management`锛岄渶 app 閫氳繃 Amazon Ads API 瀹℃牳锛夈€?
### 骞冲彴鏁版嵁鍚屾锛圫hopify / WooCommerce / TikTok Shop锛?- 銆孉PI 杩炴帴銆嶉〉濉啓鍚勫钩鍙板嚟璇佽繛鎺ュ悗锛屽埌銆屽钩鍙板悓姝ャ€嶉〉鐐?*銆岀珛鍗冲悓姝ャ€?*锛堥€夊簵閾?+ 鏁版嵁绫诲瀷锛氳鍗?鍟嗗搧/搴撳瓨锛夎Е鍙戞媺鍙栵紝缁撴灉鍦ㄥ悓姝ヤ换鍔″垪琛ㄥ彲瑙侊紙澶勭悊鏁?鐘舵€?閿欒/鑰楁椂锛夛紝澶辫触鍙噸璇曘€?- 杩炴帴鍣細`ShopifyConnector`銆乣WooCommerceConnector`銆乣TikTokConnector`锛坴202309锛岀鍚嶅緟鐪熷疄鍑瘉鑱旇皟楠岃瘉锛夈€?- TikTok 宸茬嫭绔嬫垚绗簲涓?Nav_Block锛屽叾杩炴帴鍏ュ彛搴斿湪 TikTok 鍧楀唴鍙戣捣锛坄/data-sync?platform=tiktok`锛夛紝涓嶅啀浠庣嫭绔嬬珯鍧楄繛鎺ャ€?
### AI 鎺ュ彛锛圤penAI 鍏煎锛?- 銆孉I 鎺ュ彛閰嶇疆銆嶉〉杩愯鏃堕厤缃换鎰?OpenAI 鍏煎鏈嶅姟锛圤penAI / DeepSeek / Kimi / 閫氫箟鍗冮棶 / Azure / 鏈湴 Ollama锛夈€傛湭鍚敤鏃跺悇 AI 鍔熻兘鍥為€€鍒板唴缃ā鏉裤€?- 璋冪敤鏂?Listing AI 鐢熸垚銆両nsight Agent銆佸鏈嶅洖澶嶈崏绋跨瓑;澶辫触鑷姩鍥為€€,涓嶅奖鍝嶄富娴佺▼銆?
### 褰撳墠闄愬埗锛堝瀹炶鏄庯紝鏆備笉鏀寔锛?
涓洪伩鍏嶈鐢紝浠ヤ笅鑳藉姏褰撳墠**鏆備笉鏀寔**鎴栦粛鏈夎竟鐣岋紝鐣岄潰浼氬瀹炴爣娉ㄢ€滄殏涓嶆敮鎸?鏈巿鏉冣€濓紝鑰岄潪鎻愪緵浼氶潤榛樺け璐ョ殑鍏ュ彛锛?
- **鐙珛绔欏簱瀛?鍙戣揣鍥炲啓**锛氬簱瀛樻洿鏂颁笌鍙戣揣鏍囪鐨勫悗绔紪鎺掋€佽繛鎺ョ姸鎬侀棬鎺т笌鈥滄殏涓嶆敮鎸佲€濇爣娉ㄥ潎宸插氨缁紝浣?**Shopify / WooCommerce 鐨?`PlatformWriteConnector` Bean 灏氭湭娉ㄥ唽**锛堢洰鍓嶄粎娉ㄥ唽浜?`amazon_ads` 涓?`google_ads` 涓や釜鍐欒繛鎺ュ櫒锛宍PlatformWriteConnectorConfig` 瀵圭嫭绔嬬珯骞冲彴鍥為€€涓虹┖杩炴帴鍣ㄥ垪琛級銆傚洜姝ょ嫭绔嬬珯鐨勫簱瀛?鍙戣揣鍥炲啓褰撳墠瀵瑰**鍛堢幇涓烘殏涓嶆敮鎸?*锛氬湪缂哄皯鏈夋晥杩炴帴/鍑嵁鏃剁郴缁熻繑鍥炴湭鎺堟潈涓斾笉鍒涘缓浠讳綍 Operation/Outbox銆佷笉浼€犳垚鍔燂紱寰呰ˉ榻?Shopify/WooCommerce 鍐欒繛鎺ュ櫒鍚庡嵆鍙惎鐢ㄧ湡瀹炲洖鍐欍€?- **TikTok 骞垮憡绠＄悊**锛歍ikTok 宸蹭綔涓虹嫭绔?Nav_Block锛屽綋鍓?*宸叉敮鎸?*搴楅摵杩炴帴涓庤鍗?鍟嗗搧鏁版嵁鍚屾锛?*灏氫笉鏀寔** TikTok 绔欏唴骞垮憡绠＄悊鍔ㄤ綔锛堝垱寤?璋冩暣骞垮憡绛夛級锛岀浉鍏冲叆鍙ｄ笉娓叉煋锛堟湭鏀跺綍鍒板彲瑙ｆ瀽璺敱锛夛紝涓嶆彁渚涗細闈欓粯澶辫触鐨勫叆鍙ｃ€?- **澶栭儴骞冲彴鐪熷疄鍚屾鎺堟潈**锛欰mazon Ads / SP-API / Shopify / WooCommerce / TikTok Shop 鐨勭湡瀹炴暟鎹悓姝ュ潎闇€瀵瑰簲骞冲彴鐨?OAuth/鍑嵁鎺堟潈锛涜繛鎺ユ鏋跺凡灏辩华锛屾湭鎺堟潈鏃惰繛鎺ョ姸鎬佹樉绀?`not_authorized`锛屼笉灞曠ず浼€犵殑鎴愬姛銆?- **浜氶┈閫婂箍鍛婃暟鎹椂鏁?*锛氫簹椹€婃姤琛ㄤ负闅斿ぉ锛?*T+1**锛夌敓鎴愶紝杩戝嚑鏃ユ暟鎹负**鍒濇鍊硷紙preliminary锛?*锛屼細鍥犲綊鍥犲欢杩熻淇锛屼粎缁撶畻鍚庣殑**鏈€缁堝€硷紙finalized锛?*鎵嶇ǔ瀹氾紱鐣岄潰浼氭爣娉ㄦ暟鎹姸鎬侊紝璇峰嬁褰撲綔瀹炴椂鏁版嵁浣跨敤銆?
---

## 鍗佷簩銆侀儴缃叉寚鍗?
### 閮ㄧ讲鐩爣

- **鍓嶇**锛歚https://your-domain.example`锛圢ginx 鎵樼闈欐€佽祫婧愶紝HTTPS锛?- **鍚庣**锛歚https://api.your-domain.example`锛圫pring Boot锛屽弽鍚戜唬鐞嗗埌 `127.0.0.1:8090`锛?- **鏁版嵁搴?*锛歁ySQL 8.0锛屽簱鍚?`adpilot`锛坰chema 浠?`backend-java/db/schema.sql` 鎵嬪姩瀵煎叆锛屽簲鐢ㄥ惎鍔ㄤ笉鑷姩寤鸿〃锛?
### 涓婄嚎閮ㄧ讲娴佺▼锛堟寜椤哄簭鎵ц锛?
1. **鏋勫缓鍚庣 jar**锛堝湪 `backend-java/` 涓嬶紝浣跨敤 Maven Wrapper锛夛細
   ```bash
   cd backend-java && ./mvnw clean package
   ```
   浜х墿涓?`backend-java/target/adpilot-*.jar`銆?
2. **鏋勫缓鍓嶇**锛堝湪 `frontend/` 涓嬶級锛?   ```bash
   cd frontend && pnpm install && pnpm build
   ```
   浜х墿涓?`frontend/dist/`銆?
3. **鍒涘缓 MySQL `adpilot` 鏁版嵁搴撳苟瀵煎叆 schema**锛堟暟鎹簱涓庡簲鐢ㄨВ鑰︼紝涓嶅啀浣跨敤 Flyway 鑷姩寤鸿〃锛夛細
   ```sql
   CREATE DATABASE adpilot
     CHARACTER SET utf8mb4
     COLLATE utf8mb4_unicode_ci;
   ```
   ```bash
   mysql -u aws -p adpilot < backend-java/db/schema.sql
   ```
   `db/schema.sql` 鏄敮涓€鐨?schema 鏉ユ簮锛堝惈寤鸿〃涓庣瀛愭暟鎹級锛涘悗缁?schema 鍙樻洿涔熷彧鍦ㄨ鏂囦欢鍐呬慨鏀广€?
4. **杩愯鍚庣**锛堜笉鎵ц杩佺Щ銆佷笉鑷姩鏀硅〃锛沗ddl-auto: none`銆乣flyway.enabled: false`锛夛細
   ```bash
   java -jar backend-java/target/adpilot-*.jar --spring.profiles.active=prod
   ```
   鍚庣鐩戝惉 `8090`锛岀敱 Nginx 浠?`https://api.your-domain.example` 鍙嶅悜浠ｇ悊瀵瑰鎻愪緵鏈嶅姟銆?
5. **鍙戝竷鍓嶇**锛氬皢 `frontend/dist/` 浜ょ粰 Nginx 鎵樼涓?`https://your-domain.example`銆傚墠绔瀯寤洪€氳繃 `VITE_API_BASE_URL`锛堣 `frontend/.env.production`锛夌洿鎺ヨ皟鐢ㄥ悗绔煙鍚嶏紝鍥犳鍚庣 CORS 闇€鏀捐鍓嶇鍩熷悕锛堝凡鍦?`application-prod.yml` 閰嶇疆锛夈€?
> **涓婄嚎鍓嶅繀鍋氾紙Secret Rotation锛?*锛氶儴缃插墠蹇呴』灏嗗紑鍙戦粯璁ゅ€艰疆鎹负鐢熶骇鍊尖€斺€?> - 閲嶇疆 `JWT_SECRET`锛堣嚦灏?32 浣嶉殢鏈哄瓧绗︿覆锛夛紝涓嶅緱娌跨敤寮€鍙戦粯璁ゃ€?> - 閲嶇疆鏁版嵁搴撳瘑鐮?`DB_PASSWORD`锛屼笉寰楁部鐢ㄥ紑鍙戦粯璁わ紱榛樿璐﹀彿瀵嗙爜 `Adpilot@123456` 涓嶅緱鐢ㄤ簬鐢熶骇銆?> - 鍚屾閰嶇疆 `ENCRYPTION_KEY` 绛夊叾浣欑敓浜у瘑閽ワ紙瑙佲€滅幆澧冨彉閲忔竻鍗曗€濅笌鈥滃畨鍏ㄦ鏌ユ竻鍗曗€濓級銆?
### 鏈湴鏁版嵁搴撻獙璇?
鍦ㄦ病鏈夌敓浜х幆澧冪殑鎯呭喌涓嬶紝鍙湪鏈湴鐢ㄤ竴涓┖ MySQL 8.0 搴撻獙璇?schema 瀵煎叆绔埌绔彲鐢細

1. 鍒涘缓绌哄簱锛?   ```sql
   CREATE DATABASE adpilot
     CHARACTER SET utf8mb4
     COLLATE utf8mb4_unicode_ci;
   ```

2. 瀵煎叆 schema锛堝敮涓€鏉ユ簮 `db/schema.sql`锛夛細
   ```bash
   mysql -u aws -p adpilot < backend-java/db/schema.sql
   ```

3. 閰嶇疆 JDBC 杩炴帴锛堝紑鍙?profile锛岀ず渚嬶級锛?   ```
   jdbc:mysql://localhost:3306/adpilot?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
   ```

4. 鍚姩鍚庣锛堝紑鍙?profile锛夛細
   ```bash
   cd backend-java && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

5. 棰勬湡缁撴灉锛氬悗绔湪 `ddl-auto: none`銆乣flyway.enabled: false` 涓嬪惎鍔ㄦ棤 schema 鏍￠獙閿欒銆佹棤缂鸿〃閿欒锛坰chema 瀹屽叏鐢?`db/schema.sql` 鎷ユ湁锛夈€?
### 鐜瑕佹眰

| 缁勪欢 | 鐗堟湰 | 璇存槑 |
|------|------|------|
| Java | 17+ | OpenJDK 鎴?Oracle JDK |
| Node.js | 18+ | LTS 鐗堟湰 |
| pnpm | 8+ | 鍖呯鐞嗗櫒 |
| MySQL | 8.0+ | 涓绘暟鎹簱锛圫ource of Truth锛?|
| Redis | 6+ | 缂撳瓨鍜?Token 榛戝悕鍗?|
| Maven | 3.8+ | Java 鏋勫缓宸ュ叿锛堜粨搴撳唴鍚?Maven Wrapper锛?|
| Nginx | 1.20+ | 鍙嶅悜浠ｇ悊锛堢敓浜х幆澧冿級 |

### 鐜鍙橀噺娓呭崟

| 鍙橀噺鍚?| 璇存槑 | 绀轰緥 |
|--------|------|------|
| DB_HOST | 鏁版嵁搴撲富鏈?| localhost |
| DB_PORT | 鏁版嵁搴撶鍙?| 3306 |
| DB_NAME | 鏁版嵁搴撳悕绉?| adpilot |
| DB_USERNAME | 鏁版嵁搴撶敤鎴峰悕 | aws |
| DB_PASSWORD | 鏁版嵁搴撳瘑鐮?| 锛堢敓浜х幆澧冨繀椤讳慨鏀癸級 |
| REDIS_HOST | Redis 涓绘満 | localhost |
| REDIS_PORT | Redis 绔彛 | 6379 |
| REDIS_PASSWORD | Redis 瀵嗙爜 | 锛堢敓浜х幆澧冨繀椤讳慨鏀癸級 |
| JWT_SECRET | JWT 绛惧悕瀵嗛挜 | 锛堣嚦灏?32 浣嶉殢鏈哄瓧绗︿覆锛?|
| ENCRYPTION_KEY | 鏁版嵁鍔犲瘑瀵嗛挜 | 锛堣嚦灏?16 浣嶉殢鏈哄瓧绗︿覆锛?|
| CORS_ORIGINS | 鍏佽鐨勫墠绔煙鍚?| https://your-domain.example |
| FEISHU_APP_ID | 椋炰功搴旂敤 ID | 锛堜粠椋炰功寮€鏀惧钩鍙拌幏鍙栵級 |
| FEISHU_APP_SECRET | 椋炰功搴旂敤瀵嗛挜 | 锛堜粠椋炰功寮€鏀惧钩鍙拌幏鍙栵級 |
| ADPILOT_AMAZON_ADS_CLIENT_ID | 浜氶┈閫婂箍鍛?LWA Client ID | amzn1.application-oa2-client.xxxx |
| ADPILOT_AMAZON_ADS_CLIENT_SECRET | 浜氶┈閫婂箍鍛?LWA Client Secret | 锛堜粠 Amazon LWA 搴旂敤鑾峰彇锛?|
| ADPILOT_AMAZON_ADS_REDIRECT_URI | 骞垮憡鎺堟潈鍥炶皟鍦板潃锛堥』涓?LWA 搴旂敤鐧昏涓€鑷达級 | https://your-domain.example/api-connections |

鐢熶骇閰嶇疆 `application-prod.yml` 閫氳繃涓婅堪鐜鍙橀噺娉ㄥ叆鏁版嵁婧愩€丷edis銆丣WT銆佸姞瀵嗗瘑閽ャ€丆ORS 鍩熷悕涓庨涔﹀嚟璇併€?
### Nginx 鍙嶅悜浠ｇ悊锛堢敓浜х幆澧冿級

鍓嶇涓庡悗绔垎灞炰袱涓瓙鍩燂紝鍧囪蛋 HTTPS銆傚墠绔潤鎬佹枃浠剁敱 Nginx 鎵樼涓?`your-domain.example`锛涘悗绔?`api.your-domain.example` 鍙嶅悜浠ｇ悊鍒版湰鏈?`8090`銆傚畬鏁寸ず渚嬭 `deploy/nginx.conf`锛?
```nginx
# 鍓嶇锛氭墭绠?SPA锛圚TTPS锛?server {
    listen 443 ssl;
    server_name your-domain.example;
    ssl_certificate     /etc/letsencrypt/live/your-domain.example/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.example/privkey.pem;

    root /opt/adpilot/frontend/dist;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
    client_max_body_size 50M;
}

# 鍚庣锛氬弽鍚戜唬鐞嗗埌 Spring Boot锛圚TTPS锛?server {
    listen 443 ssl;
    server_name api.your-domain.example;
    ssl_certificate     /etc/letsencrypt/live/api.your-domain.example/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.your-domain.example/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    client_max_body_size 50M;
}
```

### 澶囦唤绛栫暐

```bash
# 鏁版嵁搴撴瘡鏃ュ浠斤紙MySQL锛?mysqldump -u aws -p adpilot > backup_$(date +%Y%m%d).sql

# 鑷姩澶囦唤锛坈rontab锛屾瘡鏃?02:00锛?0 2 * * * mysqldump -u aws -p'<password>' adpilot > /backup/adpilot_$(date +\%Y\%m\%d).sql

# 澶囦唤涓婁紶鏂囦欢涓庨厤缃?tar -czf uploads_$(date +%Y%m%d).tar.gz /path/to/uploads/
tar -czf config_$(date +%Y%m%d).tar.gz /path/to/config/
```

### 鍥炴粴鏂规

```bash
# 鍓嶇锛氫繚鐣欐棫鐗堟湰 dist 鍚庡洖婊?cp -r dist dist_backup_$(date +%Y%m%d)

# 鍚庣锛氫繚鐣欐棫鐗堟湰 jar 鍚庡洖婊?cp target/adpilot-*.jar backup/
java -jar backup/adpilot-old.jar --spring.profiles.active=prod

# 鏁版嵁搴擄細浠庡浠芥仮澶?mysql -u aws -p adpilot < backup_20260607.sql
```

### 鐩戞帶寤鸿

| 缁勪欢 | 鐩戞帶椤?| 鍛婅闃堝€?|
|------|--------|---------|
| 鏈嶅姟鍣?| CPU 浣跨敤鐜?| > 80% |
| 鏈嶅姟鍣?| 鍐呭瓨浣跨敤鐜?| > 85% |
| 鏈嶅姟鍣?| 纾佺洏浣跨敤鐜?| > 90% |
| 鏁版嵁搴?| 杩炴帴鏁?| > 80% 鏈€澶ц繛鎺ユ暟 |
| 鏁版嵁搴?| 鎱㈡煡璇?| > 1 绉?|
| Redis | 鍐呭瓨浣跨敤鐜?| > 80% |
| 搴旂敤 | 鍝嶅簲鏃堕棿 | > 2 绉?|
| 搴旂敤 | 閿欒鐜?| > 1% |

---

## 鍗佷笁銆佸畨鍏ㄦ鏌ユ竻鍗?
### 瀵嗙爜涓?Token 瀹夊叏
- 瀵嗙爜浣跨敤 BCrypt 鍔犲瘑锛屼笉瀛樻槑鏂囷紱榛樿瀵嗙爜浠呴檺寮€鍙戠幆澧冿紝棣栨鐧诲綍寮哄埗鏀瑰瘑銆?- 鐧诲綍澶辫触 5 娆￠攣瀹?15 鍒嗛挓锛涘瘑鐮佸鏉傚害瑕佹眰 8 浣嶄互涓婂惈澶у皬鍐欏拰鏁板瓧銆?- JWT Secret 浠庣幆澧冨彉閲忚鍙栵紝涓嶇‖缂栫爜锛汿oken 2 灏忔椂杩囨湡锛涚櫥鍑哄悗閫氳繃 Redis 榛戝悕鍗曞け鏁堬紱鍓嶇鍙瓨 Token锛屼笉瀛樻槑鏂囧瘑鐮併€?
### API 涓庢暟鎹畨鍏?- 鎵€鏈?API 闇€璁よ瘉锛圓uthGuard 鎷︽埅锛夛紝鏃犳潈闄愯繑鍥?403锛汣ORS 闄愬埗鍩熷悕銆?- SQL 娉ㄥ叆闃叉姢锛圡yBatis 鍙傛暟鍖栨煡璇級銆乆SS 闃叉姢锛圧eact 鑷姩杞箟锛夈€丆SRF 闃叉姢锛圱oken 楠岃瘉锛夛紱寤鸿琛ュ厖 API 闄愭祦锛圧ate Limiting锛夈€?- API Token 鍔犲瘑瀛樺偍锛坄api_connections` 琛級锛涢涔?Secret 鍓嶇鑴辨晱鏄剧ず锛涙棩蹇椾笉鎵撳嵃瀵嗙爜锛涜储鍔℃暟鎹寜瑙掕壊闄愬埗鏌ョ湅鑼冨洿銆?- 鏁版嵁搴撳瘑鐮佷笉鎻愪氦浠撳簱锛坄.env` 鍦?`.gitignore`锛夛紱澶囦唤鏂囦欢涓庡鍑烘枃浠堕渶閰嶇疆璁块棶鎺у埗銆?
### 鏉冮檺涓庡鎵?- 鑿滃崟鏉冮檺銆佹寜閽潈闄愶紙PermissionGuard锛夈€丄PI 鏉冮檺锛堝悗绔嫭绔嬫牎楠岋紝涓嶄緷璧栧墠绔殣钘忥級銆佹暟鎹潈闄愶紙`data_scopes`锛夈€?- 瀹℃壒鍒嗙骇锛堜綆/涓?楂?涓ラ噸锛夛紱楂橀闄╁姩浣滐紙鍝佺墝璇嶅惁瀹氥€侀绠楀ぇ骞呰皟鏁淬€丩isting 鍙樻洿銆佷骇鍝佷笂浼犮€佹垚鏈攣瀹氥€佸埄娑﹀皝璐︼級蹇呴』瀹℃壒銆?
### 瀹¤鏃ュ織
- 鐧诲綍鎴愬姛/澶辫触銆佺敤鎴峰垱寤恒€佽鑹蹭慨鏀广€佹潈闄愬垎閰嶃€侀珮椋庨櫓鎿嶄綔銆佸鎵规搷浣滃潎璁板綍锛坄audit_logs` / `login_logs`锛夈€?
### 鐜瀹夊叏
- 鐢熶骇鐜鍏抽棴璋冭瘯涓庤缁嗗爢鏍堬紱鏂囦欢涓婁紶闄愬埗澶у皬/绫诲瀷锛汣SV 瀵煎叆鏍煎紡鏍￠獙锛涘己鍒?HTTPS锛圢ginx锛夈€?- Redis銆丮ySQL 杩炴帴鍧囪蛋鐜鍙橀噺锛屼笉纭紪鐮併€?
### 鈿狅笍 涓婄嚎鍓嶅繀鍋氶」锛圫ecret Rotation 涓庡畨鍏ㄥ熀绾匡級
1. 淇敼鎵€鏈夐粯璁ゅ瘑鐮侊紙寮€鍙戦粯璁ゅ瘑鐮?`Adpilot@123456` 涓嶅緱鐢ㄤ簬鐢熶骇锛?2. 閰嶇疆鐢熶骇鐜 `JWT_SECRET`锛堣嚦灏?32 浣嶉殢鏈哄瓧绗︿覆锛?3. 閰嶇疆鐢熶骇鐜鏁版嵁搴撳瘑鐮佷笌 `ENCRYPTION_KEY`
4. 閰嶇疆 CORS 鍏佽鍩熷悕锛堝惈 `https://your-domain.example`锛?5. 閰嶇疆 HTTPS 璇佷功
6. 閰嶇疆椋炰功鐢熶骇鐜 App 涓庡钩鍙?API 鐢熶骇鍑瘉
7. 鍏抽棴璋冭瘯妯″紡銆侀厤缃棩蹇楃骇鍒?8. 閰嶇疆澶囦唤绛栫暐涓庢枃浠惰闂帶鍒?9. 娴嬭瘯鎵€鏈夎鑹叉潈闄?
---

## 鍗佸洓銆佹暟鎹簱瀛楀吀锛圫chema Dictionary 鎽樿锛?
> 瑙勮寖鍩轰簬 MySQL 8.0銆傚畬鏁淬€佹潈濞佺殑琛ㄧ粨鏋勪互鍗曚竴鏂囦欢 `backend-java/db/schema.sql` 涓哄噯锛堟墜鍔ㄥ鍏ワ紝骞傜瓑锛夈€?
### 鍛藉悕瑙勮寖
- 琛ㄥ悕锛歚snake_case` 澶嶆暟褰㈠紡锛堝 `users`銆乣organizations`锛?- 瀛楁鍚嶏細`snake_case`锛堝 `created_at`銆乣target_acos`锛?- 涓婚敭锛歚CHAR(36)`锛圲UID锛岀敱 `UUID()` 鐢熸垚锛?- 澶栭敭锛歚{寮曠敤琛ㄥ崟鏁皚_id`锛堝 `user_id`銆乣store_id`锛?- 绱㈠紩锛歚idx_{琛ㄥ悕}_{瀛楁鍚峿`锛堝 `idx_users_email`锛?
### 閫氱敤瀛楁
鎵€鏈変笟鍔¤〃鍖呭惈锛?- `id CHAR(36) PRIMARY KEY`
- `created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)`
- `updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)`

閲嶈琛ㄨ繕鍖呭惈 `created_by` / `updated_by`锛堝紩鐢?`users(id)`锛夈€?
### 甯哥敤鏁版嵁绫诲瀷绾﹀畾
- `CHAR(36)`锛氫富閿笌澶栭敭
- `VARCHAR(n)`锛氭湁闀垮害闄愬埗鐨勭煭鏂囨湰锛沗TEXT`锛氶暱鏂囨湰
- `DECIMAL(18,4)`锛氶噾棰濓紙price銆乧ost銆乻pend銆乻ales锛夛紱`DECIMAL(10,6)`锛氱櫨鍒嗘瘮锛坅cos銆乵argin銆乧tr銆乧vr锛?- `INT`锛氳鏁帮紙inventory銆乧licks銆乮mpressions锛?- `JSON`锛氱伒娲荤粨鏋勫寲鏁版嵁
- `DATETIME(3)`锛氭椂闂存埑锛沗DATE`锛氱函鏃ユ湡锛沗TINYINT(1)`锛氬竷灏旀爣蹇?
### 鏍稿績琛ㄦ竻鍗曪紙鎸夋ā鍧楋級
- **鍐呴儴璁よ瘉**锛歚organizations`銆乣departments`銆乣users`銆乣roles`銆乣permissions`銆乣user_roles`銆乣role_permissions`銆乣user_departments`銆乣data_scopes`銆乣approval_policies`銆乣audit_logs`銆乣login_logs`
- **搴楅摵涓庝骇鍝?*锛歚marketplaces`銆乣stores`銆乣products`銆乣product_costs`銆乣product_images`
- **浠〃鐩樹笌浠诲姟**锛歚operation_tasks`銆乣reports`銆乣api_connections`
- **骞垮憡鏍稿績**锛歚goals`銆乣campaigns`銆乣ad_groups`銆乣keywords`銆乣targets`銆乣search_terms`銆乣negative_keywords`銆乣recommendations`銆乣bid_changes`銆乣budget_changes`銆乣performance_daily`
- **鍏抽敭璇嶆櫤鑳戒笌 Listing**锛歚keyword_insights`銆乣keyword_coverage`銆乣keyword_ngrams`銆乣listing_contents`銆乣listing_drafts`銆乣listing_versions`銆乣competitor_products`
- **閲囪喘/浠撳簱/鐗╂祦/璐㈠姟/瀹㈡湇/Review**锛歚suppliers`銆乣purchase_requests`銆乣purchase_orders`銆乣warehouse_locations`銆乣warehouse_inventory`銆乣cash_flow`銆乣receivables`銆乣payables`銆乣customer_tickets`銆乣customer_reviews` 绛?
### 鍏抽敭澶栭敭鍏崇郴锛堢ず渚嬶級
`users.org_id 鈫?organizations.id`锛沗stores.org_id 鈫?organizations.id`锛沗stores.marketplace_id 鈫?marketplaces.id`锛沗products.store_id 鈫?stores.id`锛沗goals.store_id 鈫?stores.id`锛沗campaigns.goal_id 鈫?goals.id`锛沗ad_groups.campaign_id 鈫?campaigns.id`锛沗keywords.ad_group_id 鈫?ad_groups.id`锛沗search_terms.campaign_id 鈫?campaigns.id`锛沗audit_logs.user_id 鈫?users.id`銆?
---

## 鍗佷簲銆佸唴閮ㄨ瘯鐢ㄤ笌 UAT 楠屾敹

### 璇曠敤鍛ㄦ湡锛堝缓璁?7 澶╋級
| 闃舵 | 澶╂暟 | 璇存槑 |
|------|------|------|
| 灏忚寖鍥磋瘯鐢?| Day 1-2 | 5 涓牳蹇冭处鍙?|
| 鎵╁ぇ璇曠敤 | Day 3-5 | 20 涓处鍙峰叏閲?|
| 闂淇 | Day 6-7 | 淇闂 + 鍥炲綊娴嬭瘯 |

### 姣忔棩楠屾敹閲嶇偣
- Day 1锛氱櫥褰曚笌鍩虹锛堣处鍙风櫥褰曘€侀娆℃敼瀵嗐€佽彍鍗曟潈闄愩€?03銆侀€€鍑猴級
- Day 2锛氫骇鍝佷笌骞垮憡锛堜骇鍝佸垪琛ㄣ€丩isting AI銆佺洰鏍?Campaign/鍏抽敭璇?鎼滅储璇嶃€丄I 寤鸿锛?- Day 3锛氭暟鎹笌鎶ヨ〃锛圕SV 瀵煎叆銆佹暟鎹川閲忋€佹姤琛ㄣ€佷粖鏃ュ緟鍔炪€佷换鍔★級
- Day 4锛氳储鍔★紙鍒╂鼎鐪嬫澘銆丼KU 鍒╂鼎銆佺粨绠椼€佸簲鏀?搴斾粯锛屼笖璐㈠姟鏃犲箍鍛婃搷浣滄寜閽級
- Day 5锛氬簱瀛樹笌閲囪喘锛堝簱瀛樺仴搴枫€佷粨搴撱€佸叆/鍑哄簱銆佺洏鐐广€佽ˉ璐с€侀噰璐鍗曘€佷緵搴斿晢銆丗BA 璐т欢锛?- Day 6锛氬鏈嶄笌瀹℃壒锛堜拱瀹舵秷鎭€佸伐鍗曘€丷eview銆丗eedback銆佸鎵逛腑蹇冦€佸璁″洖婊氥€侀涔︺€佽嚜鍔ㄥ寲锛?- Day 7锛氭€荤粨涓庤瘎浼帮紙闂姹囨€汇€佹潈闄愯皟鏁淬€佷笂绾胯瘎浼帮級

### UAT 鎸夎鑹查獙鏀?鎸夎鑹诧紙杩愯惀涓荤銆佸箍鍛婃姇鎵嬨€佽储鍔°€佺墿娴併€佷粨搴撱€侀噰璐€佸鏈嶃€佺鐞嗗眰銆佷骇鍝佺粡鐞嗐€佸彧璇昏瀵熷憳锛夊垎鍒獙璇佸彲璁块棶椤甸潰涓庢潈闄愯竟鐣岋紙鏃犳潈闄愰〉闈㈡樉绀?403锛屽彧璇昏鑹蹭笉鏄剧ず缂栬緫/瀹℃壒鎸夐挳锛夈€?
### 涓婄嚎璇勪及鏍囧噯
| 鏍囧噯 | 瑕佹眰 |
|------|------|
| 闃诲闂 | 0 涓?|
| 涓ラ噸闂 | 鈮?3 涓?|
| 鎵€鏈夎鑹插彲鐧诲綍 | 20/20 |
| 鑿滃崟鏉冮檺姝ｇ‘ | 13/13 瑙掕壊 |
| 鏍稿績娴佺▼鍙敤 | 8/8 娴佺▼ |
| 鏁版嵁瀹夊叏鏃犳硠闇?| 鏃犳硠闇?|
| 鎬ц兘鍙帴鍙?| 鍝嶅簲 < 3s |

---

## 鍗佷簲銆侀」鐩姸鎬佷笌椋庨櫓

### 鏈畬鎴愭ā鍧楋紙绛夊緟澶栭儴鎺堟潈锛?| 妯″潡 | 璇存槑 | 鐘舵€?|
|------|------|------|
| Amazon Ads API 鐪熷疄鍚屾 | 闇€瑕?OAuth 鎺堟潈 | 鈴?杩炴帴妗嗘灦宸插畬鎴愶紝绛夊緟鎺堟潈 |
| SP-API 鐪熷疄鍚屾 | 闇€瑕?LWA 鎺堟潈 | 鈴?绛夊緟鎺堟潈 |
| Shopify API 鐪熷疄鍚屾 | 闇€瑕?OAuth 鎺堟潈 | 鈴?绛夊緟鎺堟潈 |
| TikTok Shop API 鐪熷疄鍚屾 | 闇€瑕?OAuth 鎺堟潈 | 鈴?绛夊緟鎺堟潈 |
| 鐙珛绔欏簱瀛?鍙戣揣鍥炲啓 | 闇€娉ㄥ唽 Shopify/WooCommerce `PlatformWriteConnector` Bean锛堝綋鍓嶄粎 `amazon_ads`/`google_ads` 宸叉敞鍐岋級 | 鈴?缂栨帓涓庨棬鎺у凡灏辩华锛屽澶栧憟鐜扳€滄殏涓嶆敮鎸佲€?|
| TikTok 骞垮憡绠＄悊 | TikTok 绔欏唴骞垮憡鍒涘缓/璋冩暣鍔ㄤ綔 | 鈴?宸叉敮鎸佽繛鎺ヤ笌鏁版嵁鍚屾锛屽箍鍛婄鐞嗘殏涓嶆敮鎸?|

### 褰撳墠椋庨櫓椤?| 椋庨櫓 | 璇存槑 | 缂撹В鎺柦 |
|------|------|---------|
| 澶栭儴骞冲彴鏈巿鏉?| Amazon/Shopify/TikTok API 闇€瑕?OAuth | 杩炴帴妗嗘灦宸插畬鎴愶紝绛夊緟鎺堟潈 |
| 鐙珛绔欏洖鍐欒繛鎺ュ櫒缂哄け | Shopify/WooCommerce 鍐欒繛鎺ュ櫒鏈敞鍐岋紝鍥炲啓鏆備笉鍙敤 | 鐣岄潰濡傚疄鏍囨敞鈥滄殏涓嶆敮鎸佲€濓紝缂哄嚟鎹椂杩斿洖鏈巿鏉冧笖涓嶄吉閫犳垚鍔?|
| 榛樿瀵嗙爜 | 寮€鍙戠幆澧冨瘑鐮佷笉鑳界敤浜庣敓浜?| 涓婄嚎鍓嶅繀椤讳慨鏀癸紙瑙佸畨鍏ㄦ鏌ユ竻鍗曪級 |
| 杩囧害鑷姩鍖?| 鍙兘閫犳垚棰勭畻娴垂 | 榛樿鐢ㄦ埛瀹℃壒妯″紡锛岄€愭寮€鏀捐嚜鍔ㄦ墽琛?|
| 鍓嶇鎵撳寘浣撶Н | 鍓嶇鎵撳寘绾?1.3MB | 寤鸿 lazy loading 浼樺寲 |

---

## 鍗佸叚銆丄ttributions锛堢涓夋柟鑷磋阿锛?
- 鏈」鐩娇鐢?[shadcn/ui](https://ui.shadcn.com/) 缁勪欢锛岄伒寰叾 [MIT License](https://github.com/shadcn-ui/ui/blob/main/LICENSE.md)銆?- 鏈」鐩寘鍚潵鑷?[Unsplash](https://unsplash.com) 鐨勫浘鐗囷紝閬靛惊鍏?[璁稿彲鍗忚](https://unsplash.com/license)銆?
---

## 鍗佷竷銆丩icense

Private 鈥?All rights reserved.
