import { createBrowserRouter } from "react-router";
import { lazy, type ComponentType } from "react";
import { Layout } from "./components/Layout";
import { AuthGuard } from "./components/AuthGuard";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { PermissionProvider } from "./lib/PermissionContext";
import { RouteErrorBoundary } from "./components/RouteErrorBoundary";
import { LoginPage } from "./pages/LoginPage";
import { ForbiddenPage } from "./pages/ForbiddenPage";
// ─── Route-level code splitting ──────────────────────────────────────────────
// Every in-app page is lazy-loaded so the initial bundle only ships the shell
// (Layout/guards) + the landing route; each page's JS chunk is fetched on first
// navigation. `lazyNamed` adapts our named page exports to React.lazy's
// default-export contract while keeping tsc validation of the export name
// (a typo in the name fails to compile because it must be `keyof` the module).
function lazyNamed<T extends Record<string, unknown>, K extends keyof T>(
  loader: () => Promise<T>,
  name: K,
) {
  return lazy(async () => ({ default: (await loader())[name] as ComponentType<any> }));
}

const DashboardPage = lazyNamed(() => import("./pages/DashboardPage"), "DashboardPage");
const GoalsPage = lazyNamed(() => import("./pages/GoalsPage"), "GoalsPage");
const CreateGoalPage = lazyNamed(() => import("./pages/CreateGoalPage"), "CreateGoalPage");
const GoalDetailPage = lazyNamed(() => import("./pages/GoalDetailPage"), "GoalDetailPage");
const CampaignsWorkspacePage = lazyNamed(() => import("./pages/campaigns/CampaignsWorkspacePage"), "CampaignsWorkspacePage");
const AdPortfoliosPage = lazyNamed(() => import("./pages/AdPortfoliosPage"), "AdPortfoliosPage");
const ProductAdDataPage = lazyNamed(() => import("./pages/ProductAdDataPage"), "ProductAdDataPage");
const IndependentSiteHubPage = lazyNamed(() => import("./pages/IndependentSiteHubPage"), "IndependentSiteHubPage");
const IndependentSiteStorePage = lazyNamed(() => import("./pages/IndependentSiteStorePage"), "IndependentSiteStorePage");
const TikTokHubPage = lazyNamed(() => import("./pages/TikTokHubPage"), "TikTokHubPage");
const TikTokStorePage = lazyNamed(() => import("./pages/TikTokStorePage"), "TikTokStorePage");
const IndependentSiteLogisticsPage = lazyNamed(() => import("./pages/IndependentSiteLogisticsPage"), "IndependentSiteLogisticsPage");
const SmartDiagnosisPage = lazyNamed(() => import("./pages/SmartDiagnosisPage"), "SmartDiagnosisPage");
const AiNotificationsPage = lazyNamed(() => import("./pages/AiNotificationsPage"), "AiNotificationsPage");
const InsightAgentPage = lazyNamed(() => import("./pages/InsightAgentPage"), "InsightAgentPage");
const KeywordsPage = lazyNamed(() => import("./pages/KeywordsPage"), "KeywordsPage");
const KeywordLibraryPage = lazyNamed(() => import("./pages/KeywordLibraryPage"), "KeywordLibraryPage");
const SearchTermsPage = lazyNamed(() => import("./pages/SearchTermsPage"), "SearchTermsPage");
const RecommendationsPage = lazyNamed(() => import("./pages/RecommendationsPage"), "RecommendationsPage");
const ReportsPage = lazyNamed(() => import("./pages/ReportsPage"), "ReportsPage");
const ProductsPage = lazyNamed(() => import("./pages/ProductsPage"), "ProductsPage");
const StoresPage = lazyNamed(() => import("./pages/StoresPage"), "StoresPage");
const SettingsPage = lazyNamed(() => import("./pages/SettingsPage"), "SettingsPage");
const KeywordIntelligencePage = lazyNamed(() => import("./pages/KeywordIntelligencePage"), "KeywordIntelligencePage");
const ListingAIPage = lazyNamed(() => import("./pages/ListingAIPage"), "ListingAIPage");
const ProductUploadPage = lazyNamed(() => import("./pages/ProductUploadPage"), "ProductUploadPage");
const ImportsPage = lazyNamed(() => import("./pages/ImportsPage"), "ImportsPage");
const DataQualityPage = lazyNamed(() => import("./pages/DataQualityPage"), "DataQualityPage");
const ProfitDashboardPage = lazyNamed(() => import("./pages/ProfitDashboardPage"), "ProfitDashboardPage");
const ProductProfitPage = lazyNamed(() => import("./pages/ProductProfitPage"), "ProductProfitPage");
const InventoryHealthPage = lazyNamed(() => import("./pages/InventoryHealthPage"), "InventoryHealthPage");
const ReplenishmentPage = lazyNamed(() => import("./pages/ReplenishmentPage"), "ReplenishmentPage");
const CommandCenterPage = lazyNamed(() => import("./pages/CommandCenterPage"), "CommandCenterPage");
const TodayActionsPage = lazyNamed(() => import("./pages/TodayActionsPage"), "TodayActionsPage");
const TasksPage = lazyNamed(() => import("./pages/TasksPage"), "TasksPage");
const ApprovalsPage = lazyNamed(() => import("./pages/ApprovalsPage"), "ApprovalsPage");
const FeishuIntegrationPage = lazyNamed(() => import("./pages/FeishuIntegrationPage"), "FeishuIntegrationPage");
const AuditRollbackPage = lazyNamed(() => import("./pages/AuditRollbackPage"), "AuditRollbackPage");
const OrdersPage = lazyNamed(() => import("./pages/OrdersPage"), "OrdersPage");
const ReturnsPage = lazyNamed(() => import("./pages/ReturnsPage"), "ReturnsPage");
const RefundsPage = lazyNamed(() => import("./pages/RefundsPage"), "RefundsPage");
const BuyerMessagesPage = lazyNamed(() => import("./pages/BuyerMessagesPage"), "BuyerMessagesPage");
const SettlementsPage = lazyNamed(() => import("./pages/SettlementsPage"), "SettlementsPage");
const WarehousesPage = lazyNamed(() => import("./pages/WarehousesPage"), "WarehousesPage");
const SuppliersPage = lazyNamed(() => import("./pages/SuppliersPage"), "SuppliersPage");
const PurchaseOrdersPage = lazyNamed(() => import("./pages/PurchaseOrdersPage"), "PurchaseOrdersPage");
const ReviewsPage = lazyNamed(() => import("./pages/ReviewsPage"), "ReviewsPage");
const FbaShipmentsPage = lazyNamed(() => import("./pages/FbaShipmentsPage"), "FbaShipmentsPage");
const ShipmentDetailPage = lazyNamed(() => import("./pages/ShipmentDetailPage"), "ShipmentDetailPage");
const CustomerTicketsPage = lazyNamed(() => import("./pages/CustomerTicketsPage"), "CustomerTicketsPage");
const FeedbackPage = lazyNamed(() => import("./pages/FeedbackPage"), "FeedbackPage");
const ApiConnectionsPage = lazyNamed(() => import("./pages/ApiConnectionsPage"), "ApiConnectionsPage");
const PlatformSyncPage = lazyNamed(() => import("./pages/PlatformSyncPage"), "PlatformSyncPage");
const SyncLogsPage = lazyNamed(() => import("./pages/SyncLogsPage"), "SyncLogsPage");
const DataSyncWorkspacePage = lazyNamed(() => import("./pages/DataSyncWorkspacePage"), "DataSyncWorkspacePage");
const AutomationRulesPage = lazyNamed(() => import("./pages/AutomationRulesPage"), "AutomationRulesPage");
const PlacementLockPage = lazyNamed(() => import("./pages/PlacementLockPage"), "PlacementLockPage");
const RankMonitorPage = lazyNamed(() => import("./pages/RankMonitorPage"), "RankMonitorPage");
const CreativeAssetsPage = lazyNamed(() => import("./pages/CreativeAssetsPage"), "CreativeAssetsPage");
const UsersPage = lazyNamed(() => import("./pages/UsersPage"), "UsersPage");
const RolesPage = lazyNamed(() => import("./pages/RolesPage"), "RolesPage");
const DepartmentsPage = lazyNamed(() => import("./pages/DepartmentsPage"), "DepartmentsPage");
const PermissionsPage = lazyNamed(() => import("./pages/PermissionsPage"), "PermissionsPage");
const DataScopesPage = lazyNamed(() => import("./pages/DataScopesPage"), "DataScopesPage");
const LoginLogsPage = lazyNamed(() => import("./pages/LoginLogsPage"), "LoginLogsPage");
const ProfilePage = lazyNamed(() => import("./pages/ProfilePage"), "ProfilePage");
const ChangePasswordPage = lazyNamed(() => import("./pages/ChangePasswordPage"), "ChangePasswordPage");
const AISettingsPage = lazyNamed(() => import("./pages/AISettingsPage"), "AISettingsPage");
const DataInsightsPage = lazyNamed(() => import("./pages/DataInsightsPage"), "DataInsightsPage");
const AmcStudioPage = lazyNamed(() => import("./pages/AmcStudioPage"), "AmcStudioPage");
const AdMonitorWorkspacePage = lazyNamed(() => import("./pages/AdMonitorWorkspacePage"), "AdMonitorWorkspacePage");
const GoogleAdsWorkspacePage = lazyNamed(() => import("./pages/google-ads/GoogleAdsWorkspacePage"), "GoogleAdsWorkspacePage");

export const router = createBrowserRouter([
  {
    path: "/login",
    Component: LoginPage,
  },
  {
    path: "/403",
    Component: ForbiddenPage,
  },
  {
    path: "/",
    element: (
      <AuthGuard>
        <PermissionProvider>
          <Layout />
        </PermissionProvider>
      </AuthGuard>
    ),
    errorElement: <RouteErrorBoundary />,
    children: [
      // 经营中心
      { index: true, Component: CommandCenterPage },
      { path: "dashboard", element: <ProtectedRoute permission="dashboard:view"><DashboardPage /></ProtectedRoute> },
      { path: "ad-monitor", element: <ProtectedRoute permission="advertising:view"><AdMonitorWorkspacePage /></ProtectedRoute> },
      { path: "today-actions", Component: TodayActionsPage },
      { path: "approvals", element: <ProtectedRoute permission="approval:view"><ApprovalsPage /></ProtectedRoute> },

      // 销售管理
      { path: "orders", element: <ProtectedRoute permission="order:view"><OrdersPage /></ProtectedRoute> },
      { path: "returns", element: <ProtectedRoute permission="order:view"><ReturnsPage /></ProtectedRoute> },
      { path: "refunds", element: <ProtectedRoute permission="order:view"><RefundsPage /></ProtectedRoute> },
      { path: "buyer-messages", element: <ProtectedRoute permission="customer:view"><BuyerMessagesPage /></ProtectedRoute> },

      // 运营中心与运营工具：广告、商品内容和渠道发布由同一运营角色执行
      { path: "products", element: <ProtectedRoute permission="product:view"><ProductsPage /></ProtectedRoute> },
      { path: "products/:id/listing-ai", element: <ProtectedRoute permission="product:view"><ListingAIPage /></ProtectedRoute> },
      { path: "product-upload", element: <ProtectedRoute permission="product:view"><ProductUploadPage /></ProtectedRoute> },
      { path: "goals", element: <ProtectedRoute permission="advertising:view"><GoalsPage /></ProtectedRoute> },
      { path: "goals/new", element: <ProtectedRoute permission="advertising:view"><CreateGoalPage /></ProtectedRoute> },
      { path: "goals/:id", element: <ProtectedRoute permission="advertising:view"><GoalDetailPage /></ProtectedRoute> },
      { path: "campaigns", element: <ProtectedRoute permission="advertising:view"><CampaignsWorkspacePage /></ProtectedRoute> },
      { path: "ad-portfolios", element: <ProtectedRoute permission="advertising:view"><AdPortfoliosPage /></ProtectedRoute> },
      { path: "product-ad-data", element: <ProtectedRoute permission="advertising:view"><ProductAdDataPage /></ProtectedRoute> },
      { path: "smart-diagnosis", element: <ProtectedRoute permission="advertising:view"><SmartDiagnosisPage /></ProtectedRoute> },
      { path: "ai-notifications", element: <ProtectedRoute permission="advertising:view"><AiNotificationsPage /></ProtectedRoute> },
      { path: "insight-agent", element: <ProtectedRoute permission="advertising:view"><InsightAgentPage /></ProtectedRoute> },
      { path: "keywords", element: <ProtectedRoute permission="keyword:view"><KeywordsPage /></ProtectedRoute> },
      { path: "keyword-library", element: <ProtectedRoute permission="keyword:view"><KeywordLibraryPage /></ProtectedRoute> },
      { path: "search-terms", element: <ProtectedRoute permission="keyword:view"><SearchTermsPage /></ProtectedRoute> },
      { path: "keyword-intelligence", element: <ProtectedRoute permission="keyword:view"><KeywordIntelligencePage /></ProtectedRoute> },
      { path: "rank-monitor", element: <ProtectedRoute permission="keyword:view"><RankMonitorPage /></ProtectedRoute> },
      { path: "creative-assets", element: <ProtectedRoute permission="advertising:view"><CreativeAssetsPage /></ProtectedRoute> },
      { path: "recommendations", element: <ProtectedRoute permission="advertising:view"><RecommendationsPage /></ProtectedRoute> },


      // 库存与仓库
      { path: "inventory-health", element: <ProtectedRoute permission="warehouse:view"><InventoryHealthPage /></ProtectedRoute> },
      { path: "replenishment", element: <ProtectedRoute permission="warehouse:view"><ReplenishmentPage /></ProtectedRoute> },
      { path: "warehouses", element: <ProtectedRoute permission="warehouse:view"><WarehousesPage /></ProtectedRoute> },

      // 采购与供应链
      { path: "purchase-orders", element: <ProtectedRoute permission="procurement:view"><PurchaseOrdersPage /></ProtectedRoute> },
      { path: "suppliers", element: <ProtectedRoute permission="procurement:view"><SuppliersPage /></ProtectedRoute> },
      { path: "fba-shipments", element: <ProtectedRoute permission="warehouse:view"><FbaShipmentsPage /></ProtectedRoute> },
      { path: "fba-shipments/:id", element: <ProtectedRoute permission="warehouse:view"><ShipmentDetailPage /></ProtectedRoute> },

      // 财务管理
      { path: "profit-dashboard", element: <ProtectedRoute permission="finance:view"><ProfitDashboardPage /></ProtectedRoute> },
      { path: "product-profit", element: <ProtectedRoute permission="finance:view"><ProductProfitPage /></ProtectedRoute> },
      { path: "settlements", element: <ProtectedRoute permission="finance:view"><SettlementsPage /></ProtectedRoute> },

      // 客服与评价
      { path: "customer-tickets", element: <ProtectedRoute permission="customer:view"><CustomerTicketsPage /></ProtectedRoute> },
      { path: "reviews", element: <ProtectedRoute permission="review:view"><ReviewsPage /></ProtectedRoute> },
      { path: "feedback", element: <ProtectedRoute permission="review:view"><FeedbackPage /></ProtectedRoute> },

      // 自动化
      { path: "tasks", element: <ProtectedRoute permission="automation:view"><TasksPage /></ProtectedRoute> },
      { path: "automation-rules", element: <ProtectedRoute permission="automation:view"><AutomationRulesPage /></ProtectedRoute> },
      { path: "placement-locks", element: <ProtectedRoute permission="advertising:view"><PlacementLockPage /></ProtectedRoute> },
      { path: "integrations/feishu", element: <ProtectedRoute permission="feishu:view"><FeishuIntegrationPage /></ProtectedRoute> },
      { path: "audit-rollback", element: <ProtectedRoute permission="audit:view"><AuditRollbackPage /></ProtectedRoute> },

      // 数据中心
      { path: "imports", element: <ProtectedRoute permission="import:view"><ImportsPage /></ProtectedRoute> },
      { path: "data-quality", element: <ProtectedRoute permission="import:view"><DataQualityPage /></ProtectedRoute> },
      { path: "data-sync", element: <ProtectedRoute permission="import:manage"><DataSyncWorkspacePage /></ProtectedRoute> },
      { path: "api-connections", element: <ProtectedRoute permission="import:manage"><ApiConnectionsPage /></ProtectedRoute> },
      { path: "platform-sync", element: <ProtectedRoute permission="import:manage"><PlatformSyncPage /></ProtectedRoute> },
      { path: "sync-logs", element: <ProtectedRoute permission="import:view"><SyncLogsPage /></ProtectedRoute> },
      { path: "reports", element: <ProtectedRoute permission="report:view"><ReportsPage /></ProtectedRoute> },
      { path: "data-insights", element: <ProtectedRoute permission="report:view"><DataInsightsPage /></ProtectedRoute> },
      { path: "amc-studio", element: <ProtectedRoute permission="report:view"><AmcStudioPage /></ProtectedRoute> },

      // 独立站 Google Ads — 单页工作台（标签页内含 列表/报告/创建/调价/AI托管/连接）
      { path: "google-ads", element: <ProtectedRoute permission="advertising:view"><GoogleAdsWorkspacePage /></ProtectedRoute> },
      { path: "google-ads/:tab", element: <ProtectedRoute permission="advertising:view"><GoogleAdsWorkspacePage /></ProtectedRoute> },
      { path: "independent-site", element: <ProtectedRoute permission="store:view"><IndependentSiteHubPage /></ProtectedRoute> },
      { path: "independent-site/stores/:storeId", element: <ProtectedRoute permission="store:view"><IndependentSiteStorePage /></ProtectedRoute> },
      { path: "independent-site-logistics", element: <ProtectedRoute permission="product:view"><IndependentSiteLogisticsPage /></ProtectedRoute> },

      // TikTok — 工作台(已连接店铺列表) + 单店驾驶舱(概览/商品/订单)
      { path: "tiktok", element: <ProtectedRoute permission="store:view"><TikTokHubPage /></ProtectedRoute> },
      { path: "tiktok/stores/:storeId", element: <ProtectedRoute permission="store:view"><TikTokStorePage /></ProtectedRoute> },

      // 系统设置
      { path: "stores", element: <ProtectedRoute permission="store:view"><StoresPage /></ProtectedRoute> },
      { path: "users", element: <ProtectedRoute permission="user:view"><UsersPage /></ProtectedRoute> },
      { path: "roles", element: <ProtectedRoute permission="role:view"><RolesPage /></ProtectedRoute> },
      { path: "departments", element: <ProtectedRoute permission="department:view"><DepartmentsPage /></ProtectedRoute> },
      { path: "permissions", element: <ProtectedRoute permission="role:view"><PermissionsPage /></ProtectedRoute> },
      { path: "data-scopes", element: <ProtectedRoute permission="role:view"><DataScopesPage /></ProtectedRoute> },
      { path: "login-logs", element: <ProtectedRoute permission="audit:view"><LoginLogsPage /></ProtectedRoute> },
      { path: "profile", Component: ProfilePage },
      { path: "change-password", Component: ChangePasswordPage },
      { path: "settings", Component: SettingsPage },
      { path: "settings/ai", Component: AISettingsPage },
    ],
  },
]);
