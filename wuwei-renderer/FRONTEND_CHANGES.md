# GenerationCard 流式日志 — 前端改动

## 1. WuweiChat.tsx

### 1a. 第 1 行 — 加 useRef import
```
import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
```

### 1b. 第 271-282 行 — 改消息处理循环
改为:
```tsx
      } else if (msg.msgType === 'generation') {
        const allDone = msg.allDone ?? false;
        const hasError = !!msg.error;
        if (!allDone && !hasError) running = true;
        items.push({
          id: msg.id,
          role: 'assistant',
          content: '',
          isGenerationCard: true,
          steps: msg.steps ?? [],
          log: msg.log ?? [],
          allDone,
          skillId: msg.skillId ?? null,
          error: msg.error ?? null,
        });
```

### 1c. 第 61-155 行 — 替换 GenerationCardComponent 整个组件
```tsx
	const ICON: Record<string, string> = { createFile: '📄', updateFile: '✏️', readFile: '📖', deleteFile: '🗑️', listFiles: '📂', getProgress: '📊', validate: '🔍', testRun: '🧪', done: '✅' };

	const GenerationCardComponent: DataMessagePartComponent<{
	  steps?: StepState[];
	  log?: { time: string; action: string; path: string; detail?: string }[];
	  allDone?: boolean;
	  skillId?: string | null;
	  error?: string | null;
	}> = ({ data }) => {
	  const { steps, log, allDone, skillId, error } = data;
	  const { resolved: theme } = useTheme();
	  const isDark = theme === 'dark';
	  const scrollRef = useRef<HTMLDivElement>(null);
	  useEffect(() => { if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight; }, [log]);
	  const cardAccent = 'hsl(var(--primary))';
	  const hasError = !!error, isDone = !!allDone, hasLog = log && log.length > 0;
	  const latestPhase = steps && steps.length > 0 ? steps[steps.length - 1] : null;

	  return (
	    <div className="relative flex flex-col gap-2 rounded-xl text-sm overflow-hidden p-4"
	      style={{ background: 'hsl(var(--card))', minWidth: 260, maxWidth: 360,
	        boxShadow: isDark ? '0px -16px 24px 0px rgba(255,255,255,0.06) inset' : '0px -2px 8px 0px rgba(0,0,0,0.03) inset' }}>
	      <div className="flex items-center gap-2">
	        {isDone ? <span className="text-lg">✅</span> : hasError ? <span className="text-lg">❌</span> :
	          <div className="w-4 h-4 border-2 border-primary/30 border-t-primary rounded-full animate-spin" />}
	        <span className="font-semibold text-card-foreground" style={{ fontSize: '0.95rem' }}>
	          {isDone ? '生成完成' : hasError ? '生成失败' : latestPhase?.label || '正在生成技能...'}
	        </span>
	      </div>
	      {hasError && <div className="text-xs text-red-500 bg-red-500/10 rounded px-2 py-1 font-mono break-all">{error}</div>}
	      {steps && steps.length > 0 && (
	        <div className="flex flex-wrap gap-1">
	          {steps.map((s: StepState) => (
	            <span key={s.key} className="text-[10px] px-1.5 py-0.5 rounded-full font-medium"
	              style={{ background: s.status === 'done' ? 'hsl(120,50%,15%)' : s.status === 'error' ? 'hsl(0,50%,15%)' : s.status === 'in_progress' ? 'hsl(var(--primary)/0.15)' : 'hsl(var(--muted)/0.1)',
	                color: s.status === 'done' ? 'hsl(120,60%,50%)' : s.status === 'error' ? 'hsl(0,70%,50%)' : s.status === 'in_progress' ? 'hsl(var(--primary))' : 'hsl(var(--muted-foreground))' }}>
	              {(s.status === 'done' ? '✓' : s.status === 'error' ? '✗' : '○') + ' ' + s.label}
	            </span>
	          ))}
	        </div>
	      )}
	      {hasLog && (<>
	        <hr style={{ borderColor: 'hsl(var(--border))', opacity: 0.3 }} />
	        <div ref={scrollRef} className="flex flex-col gap-0.5 max-h-48 overflow-y-auto font-mono text-[11px]">
	          {log!.map((entry, i) => (
	            <div key={i} className="flex items-start gap-1.5 leading-relaxed">
	              <span className="flex-shrink-0 opacity-60" style={{ color: 'hsl(var(--muted-foreground))' }}>{entry.time}</span>
	              <span className="flex-shrink-0 w-4 text-center">{ICON[entry.action] || '•'}</span>
	              <span className="opacity-70" style={{ color: 'hsl(var(--muted-foreground))' }}>{entry.action}</span>
	              {entry.path && <span className="truncate" style={{ color: 'hsl(var(--foreground))' }}>{entry.path}</span>}
	            </div>
	          ))}
	        </div>
	      </>)}
	      {isDone && skillId && (
	        <button className="mt-1 w-full py-2 rounded-lg text-sm font-medium transition-colors"
	          style={{ background: cardAccent, color: '#fff' }}
	          onClick={() => window.dispatchEvent(new CustomEvent('view-active-skill', { detail: { skillId } }))}>
	          使用技能
	        </button>
	      )}
	    </div>
	  );
	};
```

## 2. useWuweiRuntime.ts

### 2a. DisplayMessage 接口 — 加 log, error 字段
```typescript
export interface DisplayMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  time?: string;
  isGenerationCard?: boolean;
  steps?: StepState[];
  log?: { time: string; action: string; path: string; detail?: string }[];
  allDone?: boolean;
  skillId?: string | null;
  error?: string | null;
  fileProgress?: string[];
}
```

### 2b. convertMessage — data 对象改为
```typescript
            data: {
              steps: cardSteps,
              log: msg.log ?? [],
              allDone: cardDone,
              skillId: msg.skillId ?? null,
              error: msg.error ?? null,
            },
```

## 3. ConversationStore.ts

### 3a. ChatMessage 接口
```typescript
  msgType?: string;
  steps?: import('../components/useWuweiRuntime').StepState[];
  log?: { time: string; action: string; path: string; detail?: string }[];
  skillId?: string | null;
  allDone?: boolean;
  error?: string | null;
  fileProgress?: string[];
```

### 3b. normalizeMessage — 加 log, allDone, error 字段
```typescript
    msgType: m.type as string | undefined,
    steps: m.steps as ChatMessage['steps'],
    log: m.log as ChatMessage['log'],
    skillId: m.skillId as string | null | undefined,
    allDone: m.allDone as boolean | undefined,
    error: m.error as string | null | undefined,
```
