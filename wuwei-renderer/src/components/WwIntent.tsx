import { useState, useEffect, useCallback, useRef } from 'react';
import { Button } from '@/wv-components/ui/button';
import { Tooltip } from '@/wv-components/ui/tooltip';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/wv-components/ui/select';
import { Input } from '@/wv-components/ui/input';
import { kernel } from '../kernel';

interface RouteEntry {
  provider: string;
  model: string;
  apiUrl: string;
  apiKey: string;
  params: string;
}

export function WwIntent() {
  const [value, setValue] = useState('');
  const [generating, setGenerating] = useState(false);
  const [stepDesc, setStepDesc] = useState('');
  const [refineSkillId, setRefineSkillId] = useState<string | null>(null);
  const [refineSkillName, setRefineSkillName] = useState<string | null>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Model selection state
  const [showModelPicker, setShowModelPicker] = useState(false);
  const [models, setModels] = useState<{ provider: string; model: string }[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [customApiKey, setCustomApiKey] = useState('');
  const [customApiUrl, setCustomApiUrl] = useState('');

  useEffect(() => {
    const onPlanStep = (e: Event) => {
      const d = (e as CustomEvent).detail;
      if (['generating', 'normalizing', 'auditing', 'repairing', 'installing'].includes(d.status)) {
        setGenerating(true);
        setStepDesc(d.desc);
      } else {
        setGenerating(false);
        setStepDesc('');
      }
    };
    const onSkillActivated = (e: Event) => {
      const d = (e as CustomEvent).detail;
      setRefineSkillId(d.skillId);
      setRefineSkillName(d.skillName ?? d.skillId);
    };
    const onSkillDeactivated = (e: Event) => {
      const d = (e as CustomEvent).detail;
      if (d.skillId === refineSkillId) {
        setRefineSkillId(null);
        setRefineSkillName(null);
      }
    };
    // Load model list from routing
    const onModelList = (e: Event) => {
      const entries = (e as CustomEvent).detail?.entries || {};
      const seen = new Set<string>();
      const list: { provider: string; model: string }[] = [];
      for (const [, route] of Object.entries(entries) as [string, RouteEntry][]) {
        const key = `${route.provider}/${route.model}`;
        if (!seen.has(key)) {
          seen.add(key);
          list.push({ provider: route.provider, model: route.model });
        }
      }
      setModels(list);
    };

    window.addEventListener('plan-step', onPlanStep);
    window.addEventListener('skill-activated', onSkillActivated);
    window.addEventListener('skill-deactivated', onSkillDeactivated);
    window.addEventListener('model-routing-list', onModelList);

    // Fetch model list on mount
    kernel.listModelRouting();

    return () => {
      window.removeEventListener('plan-step', onPlanStep);
      window.removeEventListener('skill-activated', onSkillActivated);
      window.removeEventListener('skill-deactivated', onSkillDeactivated);
      window.removeEventListener('model-routing-list', onModelList);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-resize textarea
  useEffect(() => {
    const el = inputRef.current;
    if (el) {
      el.style.height = 'auto';
      el.style.height = Math.min(el.scrollHeight, 120) + 'px';
    }
  }, [value]);

  const buildModelOverride = useCallback(() => {
    if (!showModelPicker) return undefined;
    const override: Record<string, string> = {};
    if (selectedProvider) override.provider = selectedProvider;
    if (selectedModel) override.model = selectedModel;
    if (customApiKey) override.apiKey = customApiKey;
    if (customApiUrl) override.apiUrl = customApiUrl;
    return Object.keys(override).length > 0 ? override : undefined;
  }, [showModelPicker, selectedProvider, selectedModel, customApiKey, customApiUrl]);

  const onSubmit = useCallback(() => {
    const text = value.trim();
    if (!text || generating) return;
    const modelOverride = buildModelOverride();
    if (refineSkillId) {
      kernel.refineSkill(refineSkillId, text, modelOverride as any);
    } else {
      kernel.sendIntent(text, modelOverride as any);
    }
    setValue('');
  }, [value, generating, refineSkillId, buildModelOverride]);

  const isRefine = !!refineSkillId;
  const placeholder = isRefine
    ? `优化 ${refineSkillName ?? 'Skill'}...`
    : '描述你想要的应用...';

  return (
    <div className="px-4 py-3">
      <div className="flex flex-col gap-1.5 max-w-3xl mx-auto">
        {/* Model selector bar */}
        <div className="flex items-center gap-2">
          {isRefine && (
            <span className="flex-shrink-0 px-2 py-0.5 rounded-md bg-accent text-xs text-accent-foreground font-medium">
              {refineSkillName}
            </span>
          )}
          <button
            className={`text-[11px] px-1.5 py-0.5 rounded border transition-colors ${
              showModelPicker
                ? 'border-ring text-foreground bg-accent'
                : 'border-transparent text-muted-foreground hover:text-foreground hover:border-border'
            }`}
            onClick={() => setShowModelPicker(!showModelPicker)}
            title="选择模型覆盖默认路由"
          >
            {showModelPicker ? '模型：自定义' : `模型：${selectedModel || '默认路由'}`}
          </button>
        </div>

        {/* Expanded model picker */}
        {showModelPicker && (
          <div className="flex items-center gap-1.5 flex-wrap">
            <Select value={selectedProvider || '__default__'} onValueChange={(v) => setSelectedProvider(v === '__default__' ? '' : v)}>
              <SelectTrigger className="h-7 text-[11px] w-[110px]">
                <SelectValue placeholder="厂商" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__default__" className="text-[11px]">默认</SelectItem>
                <SelectItem value="openai" className="text-[11px]">OpenAI</SelectItem>
                <SelectItem value="deepseek" className="text-[11px]">DeepSeek</SelectItem>
                <SelectItem value="anthropic" className="text-[11px]">Anthropic</SelectItem>
                <SelectItem value="google" className="text-[11px]">Google</SelectItem>
              </SelectContent>
            </Select>
            <Select value={selectedModel || '__default__'} onValueChange={(v) => setSelectedModel(v === '__default__' ? '' : v)}>
              <SelectTrigger className="h-7 text-[11px] flex-1 min-w-[150px]">
                <SelectValue placeholder="模型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__default__" className="text-[11px]">默认路由</SelectItem>
                {models.map((m) => (
                  <SelectItem key={`${m.provider}/${m.model}`} value={m.model} className="text-[11px]">
                    {m.provider}/{m.model}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input
              className="h-7 text-[11px] w-[120px] font-mono"
              type="password"
              placeholder="API Key"
              value={customApiKey}
              onChange={(e) => setCustomApiKey(e.target.value)}
            />
            <Input
              className="h-7 text-[11px] w-[160px] font-mono"
              placeholder="API URL"
              value={customApiUrl}
              onChange={(e) => setCustomApiUrl(e.target.value)}
            />
          </div>
        )}

        {/* Input row */}
        <div className="flex items-end gap-2">
          <div className="flex-1 relative">
            <textarea
              ref={inputRef}
              className="w-full resize-none rounded-lg border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent disabled:opacity-50"
              rows={1}
              placeholder={placeholder}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  onSubmit();
                }
              }}
              disabled={generating}
            />

            {generating && (
              <div className="absolute right-2 top-1/2 -translate-y-1/2">
                <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <span className="w-2 h-2 rounded-full bg-yellow-500 animate-pulse" />
                  {stepDesc}
                </span>
              </div>
            )}
          </div>

          <Tooltip content={isRefine ? '优化' : '生成'}>
            <Button
              size="sm"
              className="flex-shrink-0 rounded-lg"
              onClick={onSubmit}
              disabled={generating || !value.trim()}
            >
              {generating ? (
                <svg className="animate-spin" width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <circle cx="8" cy="8" r="6" strokeOpacity="0.3" />
                  <path d="M14 8a6 6 0 00-10.24-4.24" strokeLinecap="round" />
                </svg>
              ) : (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="8" y1="2" x2="8" y2="14" />
                  <polyline points="4 8 8 14 12 8" />
                </svg>
              )}
            </Button>
          </Tooltip>
        </div>
      </div>

      <p className="text-center mt-1.5 text-[10px] text-muted-foreground">
        Enter 发送 &middot; Shift+Enter 换行
        {isRefine && ' &middot; 当前为优化模式'}
      </p>
    </div>
  );
}
