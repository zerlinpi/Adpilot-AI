import { useState, useCallback, useEffect } from 'react';
import { Link, useNavigate } from 'react-router';
import {
  ArrowLeft, ArrowRight, Check, Rocket, TrendingUp, BarChart3,
  Shield, Crosshair, Layers, Tag, Zap, X, Plus, Target, Sparkles,
  type LucideIcon,
} from 'lucide-react';
import { fetchProducts, createGoal } from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { formatCurrency, formatPercent, cn } from '../lib/utils';
import { translateMachineValue } from '../lib/translateMachineValue';
import type { GoalType, RiskPreference, OptimizeFrequency } from '../types';

const iconMap: Record<string, LucideIcon> = {
  Rocket, TrendingUp, BarChart3, Shield, Crosshair, Layers, Tag, Zap,
};

const goalTypeConfigs = [
  { type: 'launch' as GoalType, label: '新品推广', description: '新品冷启动 -- 最大化曝光并收集数据', icon: 'Rocket', color: '#8B5CF6', bgColor: '#F3E8FF', defaultTargetAcos: 35, defaultRisk: 'aggressive' as RiskPreference, campaignTypes: ['auto', 'manual_keyword', 'pat'] },
  { type: 'profit' as GoalType, label: '利润目标', description: '控制 ACoS 并最大化利润率', icon: 'TrendingUp', color: '#16A34A', bgColor: '#F0FDF4', defaultTargetAcos: 20, defaultRisk: 'conservative' as RiskPreference, campaignTypes: ['manual_keyword', 'brand'] },
  { type: 'growth' as GoalType, label: '增长目标', description: '激进地扩大销售规模', icon: 'BarChart3', color: '#2563EB', bgColor: '#EFF6FF', defaultTargetAcos: 30, defaultRisk: 'aggressive' as RiskPreference, campaignTypes: ['auto', 'manual_keyword', 'category'] },
  { type: 'brand_defense' as GoalType, label: '品牌防御', description: '保护品牌关键词免受竞品抢夺', icon: 'Shield', color: '#0D9488', bgColor: '#F0FDFA', defaultTargetAcos: 10, defaultRisk: 'conservative' as RiskPreference, campaignTypes: ['brand'] },
  { type: 'competitor' as GoalType, label: '竞品定位', description: '拦截竞品产品的流量', icon: 'Crosshair', color: '#DC2626', bgColor: '#FEF2F2', defaultTargetAcos: 40, defaultRisk: 'aggressive' as RiskPreference, campaignTypes: ['competitor', 'pat'] },
  { type: 'category' as GoalType, label: '品类扩展', description: '拓展新品类关键词', icon: 'Layers', color: '#F97316', bgColor: '#FFF7ED', defaultTargetAcos: 30, defaultRisk: 'balanced' as RiskPreference, campaignTypes: ['auto', 'manual_keyword', 'category'] },
  { type: 'clearance' as GoalType, label: '清仓目标', description: '快速清空库存', icon: 'Tag', color: '#6366F1', bgColor: '#EEF2FF', defaultTargetAcos: 50, defaultRisk: 'aggressive' as RiskPreference, campaignTypes: ['auto', 'manual_keyword'] },
  { type: 'rank_boost' as GoalType, label: '排名提升', description: '将关键词排名推至首页', icon: 'Zap', color: '#EAB308', bgColor: '#FEFCE8', defaultTargetAcos: 45, defaultRisk: 'aggressive' as RiskPreference, campaignTypes: ['manual_keyword', 'category'] },
];

const STEPS = [
  { number: 1, label: '目标类型' },
  { number: 2, label: '产品' },
  { number: 3, label: '目标设置' },
  { number: 4, label: '关键词与自动化' },
];

interface FormData {
  goalType: GoalType | null;
  selectedProductIds: string[];
  goalName: string;
  targetAcos: number;
  dailyBudget: number;
  maxCpc: number;
  minBid: number;
  maxBid: number;
  riskPreference: RiskPreference;
  optimizeFrequency: OptimizeFrequency;
  brandKeywords: string[];
  categoryKeywords: string[];
  competitorBrands: string[];
  competitorAsins: string[];
  autoNegate: boolean;
  autoBid: boolean;
  autoExpand: boolean;
}

const initialFormData: FormData = {
  goalType: null,
  selectedProductIds: [],
  goalName: '',
  targetAcos: 25,
  dailyBudget: 100,
  maxCpc: 1.0,
  minBid: 0.25,
  maxBid: 2.50,
  riskPreference: 'balanced',
  optimizeFrequency: 'daily',
  brandKeywords: [],
  categoryKeywords: [],
  competitorBrands: [],
  competitorAsins: [],
  autoNegate: true,
  autoBid: true,
  autoExpand: false,
};

function TagInput({
  label,
  tags,
  onChange,
  placeholder,
}: {
  label: string;
  tags: string[];
  onChange: (tags: string[]) => void;
  placeholder: string;
}) {
  const [inputValue, setInputValue] = useState('');

  const addTag = () => {
    const trimmed = inputValue.trim();
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed]);
      setInputValue('');
    }
  };

  const removeTag = (index: number) => {
    onChange(tags.filter((_, i) => i !== index));
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      addTag();
    }
  };

  return (
    <div>
      <label className="block text-sm font-medium text-slate-700 mb-1.5">{label}</label>
      <div className="flex flex-wrap gap-1.5 mb-2 min-h-[28px]">
        {tags.map((tag, index) => (
          <span
            key={index}
            className="inline-flex items-center gap-1 px-2.5 py-1 bg-blue-50 text-blue-700 rounded-md text-xs font-medium border border-blue-200"
          >
            {tag}
            <button
              type="button"
              onClick={() => removeTag(index)}
              className="text-blue-400 hover:text-blue-600 transition-colors"
            >
              <X size={12} />
            </button>
          </span>
        ))}
      </div>
      <div className="flex gap-2">
        <input
          type="text"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          className="flex-1 h-9 rounded-lg border border-slate-200 px-3 text-sm text-slate-700 placeholder-slate-400 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
        />
        <button
          type="button"
          onClick={addTag}
          className="h-9 px-3 rounded-lg border border-slate-200 bg-white text-sm text-slate-600 hover:bg-slate-50 transition-colors flex items-center gap-1"
        >
          <Plus size={14} /> 添加
        </button>
      </div>
    </div>
  );
}

export function CreateGoalPage() {
  const navigate = useNavigate();
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [currentStep, setCurrentStep] = useState(1);
  const [formData, setFormData] = useState<FormData>(initialFormData);
  const [products, setProducts] = useState<any[]>([]);
  const [productsLoading, setProductsLoading] = useState(true);
  const [productsError, setProductsError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  // AI人格 recommendation derived from the chosen goal type. Selecting a goal
  // type only RECOMMENDS a personality — it never silently switches it; the
  // operator must explicitly apply/confirm the recommendation (Req 49.8).
  const [recommendedRisk, setRecommendedRisk] = useState<RiskPreference | null>(null);

  useEffect(() => {
    if (!storeId) return;
    fetchProducts(storeId)
      .then((data: any) => setProducts(Array.isArray(data) ? data : data?.items || []))
      .catch((err) => setProductsError(err.message))
      .finally(() => setProductsLoading(false));
  }, [storeId]);

  const updateForm = useCallback(<K extends keyof FormData>(key: K, value: FormData[K]) => {
    setFormData((prev) => ({ ...prev, [key]: value }));
  }, []);

  const selectedConfig = goalTypeConfigs.find((c) => c.type === formData.goalType);

  const canProceed = () => {
    switch (currentStep) {
      case 1: return formData.goalType !== null;
      case 2: return formData.selectedProductIds.length > 0;
      case 3: return formData.goalName.trim().length > 0 && formData.dailyBudget > 0;
      case 4: return true;
      default: return false;
    }
  };

  const handleNext = () => {
    if (currentStep < 4) setCurrentStep(currentStep + 1);
  };

  const handleBack = () => {
    if (currentStep > 1) setCurrentStep(currentStep - 1);
  };

  const handleSubmit = async () => {
    try {
      setSubmitting(true);
      setSubmitError(null);
      const goal = await createGoal({
        storeId: storeId!,
        name: formData.goalName,
        type: formData.goalType!,
        targetAcos: formData.targetAcos,
        dailyBudget: formData.dailyBudget,
        maxCpc: formData.maxCpc,
        minBid: formData.minBid,
        maxBid: formData.maxBid,
        brandKeywords: formData.brandKeywords,
        categoryKeywords: formData.categoryKeywords,
        competitorBrands: formData.competitorBrands,
        competitorAsins: formData.competitorAsins,
        autoNegate: formData.autoNegate,
        autoBid: formData.autoBid,
        autoExpand: formData.autoExpand,
        optimizeFrequency: formData.optimizeFrequency,
        riskPreference: formData.riskPreference,
        productIds: formData.selectedProductIds,
      });
      navigate(`/goals/${goal.id}`);
    } catch (err: any) {
      setSubmitError(err.message || '创建目标失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Page Header */}
      <div className="flex items-center gap-4">
        <Link
          to="/goals"
          className="w-9 h-9 rounded-lg border border-slate-200 bg-white flex items-center justify-center text-slate-400 hover:text-slate-600 hover:border-slate-300 transition-colors"
        >
          <ArrowLeft size={16} />
        </Link>
        <div>
          <h1 className="text-2xl font-bold text-slate-900">创建目标</h1>
          <p className="text-sm text-slate-500 mt-0.5">设置新的广告目标并启用活动自动化</p>
        </div>
      </div>

      {/* Progress Indicator */}
      <div className="bg-white rounded-xl border border-slate-200 px-6 py-4">
        <div className="flex items-center justify-between">
          {STEPS.map((step, index) => (
            <div key={step.number} className="flex items-center">
              <div className="flex items-center gap-3">
                <div
                  className={cn(
                    'w-8 h-8 rounded-full flex items-center justify-center text-sm font-semibold transition-colors',
                    currentStep > step.number
                      ? 'bg-blue-600 text-white'
                      : currentStep === step.number
                        ? 'bg-blue-600 text-white'
                        : 'bg-slate-100 text-slate-400',
                  )}
                >
                  {currentStep > step.number ? <Check size={16} /> : step.number}
                </div>
                <span
                  className={cn(
                    'text-sm font-medium',
                    currentStep >= step.number ? 'text-slate-900' : 'text-slate-400',
                  )}
                >
                  {step.label}
                </span>
              </div>
              {index < STEPS.length - 1 && (
                <div
                  className={cn(
                    'w-16 sm:w-24 h-0.5 mx-3 rounded-full',
                    currentStep > step.number ? 'bg-blue-600' : 'bg-slate-200',
                  )}
                />
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Step Content */}
      <div className="bg-white rounded-xl border border-slate-200 p-6">
        {/* Step 1: Goal Type Selection */}
        {currentStep === 1 && (
          <div>
            <h2 className="text-lg font-semibold text-slate-900 mb-1">选择目标类型</h2>
            <p className="text-sm text-slate-500 mb-6">选择最符合您目标的广告策略</p>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              {goalTypeConfigs.map((config) => {
                const Icon = iconMap[config.icon] || Target;
                const isSelected = formData.goalType === config.type;
                return (
                  <button
                    key={config.type}
                    type="button"
                    onClick={() => {
                      updateForm('goalType', config.type);
                      updateForm('targetAcos', config.defaultTargetAcos);
                      // Do NOT silently switch the AI personality on goal-type
                      // selection (Req 49.8); only surface a recommendation the
                      // operator can explicitly apply in the AI人格 step.
                      setRecommendedRisk(config.defaultRisk);
                      if (!formData.goalName) {
                        updateForm('goalName', config.label);
                      }
                    }}
                    className={cn(
                      'relative text-left p-4 rounded-xl border-2 transition-all duration-200 hover:shadow-md',
                      isSelected
                        ? 'border-blue-500 bg-blue-50/50 shadow-sm'
                        : 'border-slate-200 hover:border-slate-300',
                    )}
                  >
                    {isSelected && (
                      <div className="absolute top-3 right-3 w-5 h-5 rounded-full bg-blue-600 flex items-center justify-center">
                        <Check size={12} className="text-white" />
                      </div>
                    )}
                    <div
                      className="w-10 h-10 rounded-lg flex items-center justify-center mb-3"
                      style={{ backgroundColor: config.bgColor }}
                    >
                      <Icon size={20} style={{ color: config.color }} />
                    </div>
                    <h3 className="text-sm font-semibold text-slate-900 mb-1">{config.label}</h3>
                    <p className="text-xs text-slate-500 leading-relaxed">{config.description}</p>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Step 2: Product Selection */}
        {currentStep === 2 && (
          <div>
            <h2 className="text-lg font-semibold text-slate-900 mb-1">选择产品</h2>
            <p className="text-sm text-slate-500 mb-6">选择要包含在此目标中的产品</p>
            <div className="space-y-2">
              {products.map((product) => {
                const isSelected = formData.selectedProductIds.includes(product.id);
                return (
                  <label
                    key={product.id}
                    className={cn(
                      'flex items-center gap-4 p-4 rounded-xl border-2 cursor-pointer transition-all',
                      isSelected
                        ? 'border-blue-500 bg-blue-50/50'
                        : 'border-slate-200 hover:border-slate-300',
                    )}
                  >
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => {
                        const next = isSelected
                          ? formData.selectedProductIds.filter((id) => id !== product.id)
                          : [...formData.selectedProductIds, product.id];
                        updateForm('selectedProductIds', next);
                      }}
                      className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-900 truncate">{product.name}</p>
                      <div className="flex items-center gap-4 mt-1 text-xs text-slate-500">
                        <span>SKU: {product.sku}</span>
                        <span>ASIN: {product.asin}</span>
                      </div>
                    </div>
                    <div className="text-right flex-shrink-0">
                      <p className="text-sm font-semibold text-slate-900">{formatCurrency(product.price)}</p>
                      <p className="text-xs text-slate-500">毛利率：{formatPercent(product.grossMargin)}</p>
                    </div>
                    <span
                      className={cn(
                        'px-2 py-0.5 rounded-md text-xs font-medium',
                        product.status === 'active'
                          ? 'bg-emerald-100 text-emerald-700'
                          : 'bg-slate-100 text-slate-500',
                      )}
                    >
                      {product.status === 'out_of_stock' ? '缺货' : product.status === 'active' ? '在售' : product.status}
                    </span>
                  </label>
                );
              })}
            </div>
          </div>
        )}

        {/* Step 3: Target Configuration */}
        {currentStep === 3 && (
          <div className="space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-slate-900 mb-1">配置目标</h2>
              <p className="text-sm text-slate-500">设置您的绩效目标和预算参数</p>
            </div>

            {/* Goal Name */}
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">目标名称</label>
              <input
                type="text"
                value={formData.goalName}
                onChange={(e) => updateForm('goalName', e.target.value)}
                placeholder="例如：X100 利润最大化"
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm text-slate-700 placeholder-slate-400 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
            </div>

            {/* Target ACoS Slider */}
            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className="text-sm font-medium text-slate-700">目标 ACoS</label>
                <span className="text-sm font-semibold text-blue-600">{formatPercent(formData.targetAcos)}</span>
              </div>
              <input
                type="range"
                min={5}
                max={80}
                step={1}
                value={formData.targetAcos}
                onChange={(e) => updateForm('targetAcos', Number(e.target.value))}
                className="w-full h-2 bg-slate-200 rounded-lg appearance-none cursor-pointer accent-blue-600"
              />
              <div className="flex justify-between mt-1 text-xs text-slate-400">
                <span>5%</span>
                <span>80%</span>
              </div>
            </div>

            {/* Budget & CPC Row */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">日预算 ($)</label>
                <input
                  type="number"
                  min={1}
                  value={formData.dailyBudget}
                  onChange={(e) => updateForm('dailyBudget', Number(e.target.value))}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">最高 CPC ($)</label>
                <input
                  type="number"
                  min={0.01}
                  step={0.05}
                  value={formData.maxCpc}
                  onChange={(e) => updateForm('maxCpc', Number(e.target.value))}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                />
              </div>
            </div>

            {/* Min/Max Bid */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">最低竞价 ($)</label>
                <input
                  type="number"
                  min={0.01}
                  step={0.05}
                  value={formData.minBid}
                  onChange={(e) => updateForm('minBid', Number(e.target.value))}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">最高竞价 ($)</label>
                <input
                  type="number"
                  min={0.01}
                  step={0.05}
                  value={formData.maxBid}
                  onChange={(e) => updateForm('maxBid', Number(e.target.value))}
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
                />
              </div>
            </div>

            {/* AI Personality (人格) — recommended by goal type, applied only on explicit confirmation (Req 49.8) */}
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-3">AI人格</label>

              {recommendedRisk && recommendedRisk !== formData.riskPreference && (
                <div className="mb-3 flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2.5">
                  <Sparkles size={15} className="mt-0.5 shrink-0 text-blue-500" />
                  <div className="flex-1">
                    <p className="text-xs text-blue-800">
                      根据所选目标类型，推荐使用「{translateMachineValue('AI_Personality', recommendedRisk)}」人格。
                      当前为「{translateMachineValue('AI_Personality', formData.riskPreference)}」，是否应用推荐？
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => updateForm('riskPreference', recommendedRisk)}
                    className="shrink-0 rounded-md bg-blue-600 px-2.5 py-1 text-xs font-medium text-white hover:bg-blue-700"
                  >
                    应用推荐
                  </button>
                </div>
              )}

              <div className="grid grid-cols-3 gap-3">
                {(['conservative', 'balanced', 'aggressive'] as RiskPreference[]).map((risk) => {
                  const isRecommended = recommendedRisk === risk;
                  return (
                    <button
                      key={risk}
                      type="button"
                      onClick={() => updateForm('riskPreference', risk)}
                      className={cn(
                        'relative p-3 rounded-xl border-2 text-center transition-all',
                        formData.riskPreference === risk
                          ? 'border-blue-500 bg-blue-50/50'
                          : 'border-slate-200 hover:border-slate-300',
                      )}
                    >
                      {isRecommended && (
                        <span className="absolute top-1.5 right-1.5 rounded-full bg-blue-100 px-1.5 py-0.5 text-[9px] font-medium text-blue-600">
                          推荐
                        </span>
                      )}
                      <p className={cn(
                        'text-sm font-semibold',
                        formData.riskPreference === risk ? 'text-blue-700' : 'text-slate-700',
                      )}>
                        {translateMachineValue('AI_Personality', risk)}
                      </p>
                      <p className="text-xs text-slate-400 mt-0.5">
                        {risk === 'conservative' ? '安全竞价，低花费' : risk === 'balanced' ? '中等风险/回报' : '最大曝光，高花费'}
                      </p>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Optimize Frequency */}
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">优化频率</label>
              <select
                value={formData.optimizeFrequency}
                onChange={(e) => updateForm('optimizeFrequency', e.target.value as OptimizeFrequency)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                <option value="daily">每日</option>
                <option value="hourly_12">每 12 小时</option>
                <option value="hourly">每小时</option>
              </select>
            </div>
          </div>
        )}

        {/* Step 4: Keywords & Automation */}
        {currentStep === 4 && (
          <div className="space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-slate-900 mb-1">关键词与自动化</h2>
              <p className="text-sm text-slate-500">配置关键词定位和自动化规则</p>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
              <TagInput
                label="品牌关键词"
                tags={formData.brandKeywords}
                onChange={(tags) => updateForm('brandKeywords', tags)}
                placeholder="例如：audiomax、audio max"
              />
              <TagInput
                label="品类关键词"
                tags={formData.categoryKeywords}
                onChange={(tags) => updateForm('categoryKeywords', tags)}
                placeholder="例如：wireless headphones"
              />
              <TagInput
                label="竞品品牌"
                tags={formData.competitorBrands}
                onChange={(tags) => updateForm('competitorBrands', tags)}
                placeholder="例如：Sony、Bose"
              />
              <TagInput
                label="竞品 ASIN"
                tags={formData.competitorAsins}
                onChange={(tags) => updateForm('competitorAsins', tags)}
                placeholder="例如：B09XTCGJ9K"
              />
            </div>

            {/* Automation Toggles */}
            <div>
              <h3 className="text-sm font-medium text-slate-700 mb-3">自动化规则</h3>
              <div className="space-y-3">
                {[
                  { key: 'autoNegate' as const, label: '自动否定', description: '自动为表现不佳的搜索词添加否定关键词' },
                  { key: 'autoBid' as const, label: '自动竞价优化', description: 'AI 根据绩效数据和目标自动调整竞价' },
                  { key: 'autoExpand' as const, label: '自动扩展关键词', description: '自动发现并添加新的关键词机会' },
                ].map(({ key, label, description }) => (
                  <label
                    key={key}
                    className="flex items-center justify-between p-4 rounded-xl border border-slate-200 cursor-pointer hover:bg-slate-50 transition-colors"
                  >
                    <div>
                      <p className="text-sm font-medium text-slate-900">{label}</p>
                      <p className="text-xs text-slate-500 mt-0.5">{description}</p>
                    </div>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={formData[key]}
                      onClick={() => updateForm(key, !formData[key])}
                      className={cn(
                        'relative inline-flex h-6 w-11 items-center rounded-full transition-colors',
                        formData[key] ? 'bg-blue-600' : 'bg-slate-200',
                      )}
                    >
                      <span
                        className={cn(
                          'inline-block h-4 w-4 rounded-full bg-white shadow-sm transition-transform',
                          formData[key] ? 'translate-x-6' : 'translate-x-1',
                        )}
                      />
                    </button>
                  </label>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Navigation Footer */}
      <div className="flex items-center justify-between">
        <button
          type="button"
          onClick={handleBack}
          disabled={currentStep === 1}
          className={cn(
            'inline-flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-medium transition-colors',
            currentStep === 1
              ? 'text-slate-300 cursor-not-allowed'
              : 'text-slate-600 bg-white border border-slate-200 hover:bg-slate-50',
          )}
        >
          <ArrowLeft size={16} />
          上一步
        </button>

        {currentStep < 4 ? (
          <button
            type="button"
            onClick={handleNext}
            disabled={!canProceed()}
            className={cn(
              'inline-flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-medium transition-colors',
              canProceed()
                ? 'bg-blue-600 text-white hover:bg-blue-700 shadow-sm'
                : 'bg-slate-100 text-slate-400 cursor-not-allowed',
            )}
          >
            下一步
            <ArrowRight size={16} />
          </button>
        ) : (
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!storeId || submitting}
            className={cn(
              'inline-flex items-center gap-2 px-6 py-2.5 rounded-lg text-sm font-medium transition-colors shadow-sm',
              !storeId || submitting
                ? 'bg-slate-100 text-slate-400 cursor-not-allowed'
                : 'bg-blue-600 text-white hover:bg-blue-700',
            )}
          >
            <Zap size={16} />
            生成广告活动结构
          </button>
        )}
      </div>
    </div>
  );
}
