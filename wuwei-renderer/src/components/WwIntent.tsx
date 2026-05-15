import { useState, useEffect, useCallback, useRef } from 'react';
import { Button } from '@/wv-components/ui/button';
import { Tooltip } from '@/wv-components/ui/tooltip';
import { kernel } from '../kernel';

export function WwIntent() {
  const [value, setValue] = useState('');
  const [generating, setGenerating] = useState(false);
  const [stepDesc, setStepDesc] = useState('');
  const [refineSkillId, setRefineSkillId] = useState<string | null>(null);
  const [refineSkillName, setRefineSkillName] = useState<string | null>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

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

    window.addEventListener('plan-step', onPlanStep);
    window.addEventListener('skill-activated', onSkillActivated);
    window.addEventListener('skill-deactivated', onSkillDeactivated);

    return () => {
      window.removeEventListener('plan-step', onPlanStep);
      window.removeEventListener('skill-activated', onSkillActivated);
      window.removeEventListener('skill-deactivated', onSkillDeactivated);
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

  const onSubmit = useCallback(() => {
    const text = value.trim();
    if (!text || generating) return;
    if (refineSkillId) {
      kernel.refineSkill(refineSkillId, text);
    } else {
      kernel.sendIntent(text);
    }
    setValue('');
  }, [value, generating, refineSkillId]);

  const isRefine = !!refineSkillId;
  const placeholder = isRefine
    ? `优化 ${refineSkillName ?? 'Skill'}...`
    : '描述你想要的应用...';

  return (
    <div className="px-4 py-3">
      <div className="flex items-end gap-2 max-w-3xl mx-auto">
        {/* Context badge */}
        {isRefine && (
          <span className="flex-shrink-0 mb-2 px-2 py-0.5 rounded-md bg-accent text-xs text-accent-foreground font-medium">
            {refineSkillName}
          </span>
        )}

        {/* Input area */}
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

          {/* Generating indicator */}
          {generating && (
            <div className="absolute right-2 top-1/2 -translate-y-1/2">
              <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <span className="w-2 h-2 rounded-full bg-yellow-500 animate-pulse" />
                {stepDesc}
              </span>
            </div>
          )}
        </div>

        {/* Send button */}
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

      {/* Hint text */}
      <p className="text-center mt-1.5 text-[10px] text-muted-foreground">
        Enter 发送 &middot; Shift+Enter 换行
        {isRefine && ' &middot; 当前为优化模式'}
      </p>
    </div>
  );
}
