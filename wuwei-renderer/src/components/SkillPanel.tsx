import { type ReactNode, useState } from 'react';
import { WwWorkspace } from './WwWorkspace';
import { WwWorkbench } from './WwWorkbench';
import { kernel } from '../kernel';
import {
  Sheet,
  SheetContent,
} from '@/wv-components/ui/sheet';

interface SkillPanelProps {
  /** Active skill ID — null/undefined means no active skill */
  skillId?: string | null;
  /** Thread scope for WwWorkspace */
  activeThreadId?: string | null;
  /** Replay data from skill-activated event (for panels that mount after the event) */
  initDetail?: Record<string, unknown> | null;
  /** Called when user wants to deactivate/stop the skill */
  onDeactivate?: () => void;
  /** Placeholder content when no skill is active */
  placeholder?: ReactNode;
  /** Show header bar with skill name and actions */
  showHeader?: boolean;
  /** Optional version badge (e.g. "1.0.0") */
  version?: string;
  /** Optional status for the dot color */
  status?: string;
  /** Optional capability names to show as badges */
  capabilities?: Record<string, unknown>;
}

function statusDotClass(status?: string) {
  switch (status) {
    case 'running': return 'bg-green-500';
    case 'error': return 'bg-red-500';
    case 'loading': return 'bg-yellow-500 animate-pulse';
    default: return 'bg-green-500';
  }
}

export function SkillPanel({
  skillId,
  activeThreadId,
  initDetail,
  onDeactivate,
  placeholder,
  showHeader = true,
  version,
  status,
  capabilities,
}: SkillPanelProps) {
  const hasSkill = !!skillId;
  const displayName = (initDetail?.skillName as string) || skillId || '';
  const [sourceOpen, setSourceOpen] = useState(false);

  function handleViewSource() {
    if (!skillId) return;
    kernel.send({ type: 'get-skill-source', skillId });
    setSourceOpen(true);
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header — shared across all modes */}
      {showHeader && hasSkill && (
        <div className="flex items-center gap-2 px-4 py-2 border-b flex-shrink-0">
          <span className={`w-2 h-2 rounded-full ${statusDotClass(status)}`} />
          <span className="text-sm font-medium truncate">{displayName}</span>
          {version && (
            <span className="inline-flex items-center rounded-md bg-secondary px-1.5 py-0.5 text-[10px] font-medium text-secondary-foreground">
              v{version}
            </span>
          )}
          {capabilities && Object.keys(capabilities).filter(c => c !== 'ui' && c !== 'permission').map(c => (
            <span key={c} className="inline-flex items-center rounded-md border px-1.5 py-0.5 text-[10px] text-muted-foreground" title={c}>
              {c}
            </span>
          ))}
          <div className="ml-auto flex items-center gap-0.5">
            <button
              className="p-1 rounded hover:bg-accent transition-colors text-muted-foreground hover:text-foreground flex-shrink-0"
              onClick={handleViewSource}
              title="查看源码"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="16 18 22 12 16 6" />
                <polyline points="8 6 2 12 8 18" />
              </svg>
            </button>
            {onDeactivate && (
              <button
                className="p-1 rounded hover:bg-accent transition-colors text-muted-foreground hover:text-foreground flex-shrink-0"
                onClick={onDeactivate}
                title="停用"
              >
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                  <line x1="4" y1="4" x2="12" y2="12" />
                  <line x1="12" y1="4" x2="4" y2="12" />
                </svg>
              </button>
            )}
          </div>
        </div>
      )}

      {/* WwWorkspace — flex child that fills remaining space */}
      {hasSkill && (
        <div className="flex-1 min-h-0 flex flex-col" style={{ padding: 20 }}>
          <WwWorkspace key={skillId ?? 'no-skill'} activeThreadId={activeThreadId} initDetail={initDetail} />
        </div>
      )}

      {/* Placeholder */}
      {!hasSkill && placeholder && (
        <div className="flex-1 flex items-center justify-center text-muted-foreground">
          {placeholder}
        </div>
      )}

      {/* Source viewer Sheet */}
      <Sheet open={sourceOpen} onOpenChange={setSourceOpen}>
        <SheetContent side="right" className="!w-screen !max-w-none p-0">
          <WwWorkbench onClose={() => setSourceOpen(false)} />
        </SheetContent>
      </Sheet>
    </div>
  );
}
